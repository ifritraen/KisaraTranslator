import cv2
import numpy as np
import onnxruntime as ort
from typing import List, Tuple, Optional
from .types import Rect, TextLineItem, CrunchPartitionItem

class ConvexityDefectDetector:
    """
    1:1 Port of ConvexityDefectDetector.kt for PC Simulator.
    Sub-pixel concavity notch & convexity defect candidate detector for speech bubbles.
    """
    @staticmethod
    def detect_candidates(crop_bgr: np.ndarray) -> List[Tuple[int, int]]:
        h, w = crop_bgr.shape[:2]
        if w < 12 or h < 12:
            return []

        # 1. Adaptive brightness thresholding
        gray = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2GRAY)
        hist, _ = np.histogram(gray, bins=256, range=(0, 256))
        target_count = int(0.95 * (w * h))
        accum = 0
        p95 = 200
        for v in range(256):
            accum += hist[v]
            if accum >= target_count:
                p95 = v
                break
        t = int(np.clip(p95 * 0.90, 140, 225))
        mask = (gray >= t).astype(np.uint8)

        # 2. Extract outer contour using OpenCV findContours with Shoelace area >= 1500
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        if not contours:
            return []

        best_contour = max(contours, key=lambda c: cv2.contourArea(c))
        if cv2.contourArea(best_contour) < 1500.0 or len(best_contour) < 10:
            return []

        # 3. Polygon approximation with epsilon = 2.0
        approx = cv2.approxPolyDP(best_contour, epsilon=2.0, closed=True)
        if len(approx) < 5:
            return []

        # 4. Convex hull and convexity defects
        hull = cv2.convexHull(approx, returnPoints=False)
        if hull is None or len(hull) <= 3:
            return []

        hull = np.sort(hull, axis=0)
        try:
            defects = cv2.convexityDefects(approx, hull)
        except Exception:
            defects = None

        if defects is None:
            return []

        min_depth = max(5.0 * 256.0, min(w, h) * 0.025 * 256.0)  # OpenCV fixed-point / 256
        defect_candidates = []
        defects = defects.reshape(-1, 4)
        for i in range(len(defects)):
            s, e, f, d = defects[i]
            if d >= min_depth:
                far_pt = approx[f, 0]
                defect_candidates.append((int(far_pt[0]), int(far_pt[1]), float(d)))

        # 5. Sort by depth descending and deduplicate within 8px radius
        defect_candidates.sort(key=lambda item: item[2], reverse=True)
        candidates: List[Tuple[int, int]] = []
        for x, y, _ in defect_candidates:
            is_dup = any((x - cx) ** 2 + (y - cy) ** 2 < 64 for cx, cy in candidates)
            if not is_dup:
                candidates.append((x, y))

        return candidates

class Method3WaistEngine:
    """
    1:1 Port of Method3WaistEngine.kt for PC Simulator.
    Runs YOLOv8-pose inference on speech bubble crops, snaps keypoints to OpenCV concavity defects,
    and partitions Module 2 vertical lines into Lobe A and Lobe B.
    """
    TARGET_DIM = 640
    SEARCH_RADIUS = 25

    def __init__(self, model_path: str):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.inter_op_num_threads = 1
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(model_path, opts)
        self.input_name = self.session.get_inputs()[0].name

    def predict_crop(self, crop_bgr: np.ndarray):
        h0, w0 = crop_bgr.shape[:2]
        if w0 < 8 or h0 < 8:
            return None

        scale = min(self.TARGET_DIM / float(w0), self.TARGET_DIM / float(h0))
        nw = max(1, min(self.TARGET_DIM, int(round(w0 * scale))))
        nh = max(1, min(self.TARGET_DIM, int(round(h0 * scale))))
        dx = (self.TARGET_DIM - nw) // 2
        dy = (self.TARGET_DIM - nh) // 2

        canvas = np.full((self.TARGET_DIM, self.TARGET_DIM, 3), 114, dtype=np.uint8)
        scaled_crop = cv2.resize(crop_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
        canvas[dy:dy+nh, dx:dx+nw] = scaled_crop

        rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) * (1.0 / 255.0)
        blob = np.transpose(rgb, (2, 0, 1))[np.newaxis, ...]  # [1, 3, 640, 640]

        outputs = self.session.run(None, {self.input_name: blob})
        out = outputs[0][0]  # [12, 8400]

        num_candidates = out.shape[1]
        c0_arr = out[4, :]
        c1_arr = out[5, :]
        max_scores = np.maximum(c0_arr, c1_arr)
        best_idx = int(np.argmax(max_scores))

        c0 = float(c0_arr[best_idx])
        c1 = float(c1_arr[best_idx])
        raw_k0x = float(out[6, best_idx])
        raw_k0y = float(out[7, best_idx])
        raw_k1x = float(out[9, best_idx])
        raw_k1y = float(out[10, best_idx])

        # Map back to crop coordinates
        k0x = (raw_k0x - dx) / scale
        k0y = (raw_k0y - dy) / scale
        k1x = (raw_k1x - dx) / scale
        k1y = (raw_k1y - dy) / scale

        # Canonical order
        if k0x > k1x or (k0x == k1x and k0y > k1y):
            k0x, k1x = k1x, k0x
            k0y, k1y = k1y, k0y

        candidates = ConvexityDefectDetector.detect_candidates(crop_bgr)
        tau_snap = max(45.0, 0.12 * min(float(w0), float(h0)))
        s0 = self._snap_to_candidate_or_ink(k0x, k0y, candidates, crop_bgr, tau_snap)
        s1 = self._snap_to_candidate_or_ink(k1x, k1y, candidates, crop_bgr, tau_snap, used_candidate=s0)

        return {
            "is_conjoined": c0 >= c1,
            "conf_conj": c0,
            "conf_single": c1,
            "p1_raw": (int(round(k0x)), int(round(k0y))),
            "p2_raw": (int(round(k1x)), int(round(k1y))),
            "p1_snapped": s0,
            "p2_snapped": s1,
            "candidates": candidates
        }

    def _snap_to_candidate_or_ink(
        self,
        pt_x: float,
        pt_y: float,
        candidates: List[Tuple[int, int]],
        crop_bgr: np.ndarray,
        snap_radius: float,
        used_candidate: Optional[Tuple[int, int]] = None
    ) -> Tuple[int, int]:
        best_cand = None
        min_dist = float('inf')
        for cand in candidates:
            if cand == used_candidate:
                continue
            dx = cand[0] - pt_x
            dy = cand[1] - pt_y
            d = np.sqrt(dx * dx + dy * dy)
            if d <= snap_radius and d < min_dist:
                min_dist = d
                best_cand = cand

        if best_cand is not None:
            return best_cand
        return self._snap_to_ink(crop_bgr, pt_x, pt_y, int(round(snap_radius * 0.6)))

    def _snap_to_ink(self, crop_bgr: np.ndarray, pt_x: float, pt_y: float, search_r: int = 25) -> Tuple[int, int]:
        h, w = crop_bgr.shape[:2]
        x0 = int(np.clip(round(pt_x), 0, w - 1))
        y0 = int(np.clip(round(pt_y), 0, h - 1))
        x1 = max(0, x0 - search_r)
        x2 = min(w - 1, x0 + search_r)
        y1 = max(0, y0 - search_r)
        y2 = min(h - 1, y0 + search_r)
        if x2 < x1 or y2 < y1:
            return (x0, y0)

        patch = crop_bgr[y1:y2+1, x1:x2+1]
        if patch.size == 0 or patch.shape[0] == 0 or patch.shape[1] == 0:
            return (x0, y0)
        gray = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY)
        dark_y, dark_x = np.where(gray < 100)

        if len(dark_x) == 0:
            return (int(np.clip(x0, 0, w - 1)), int(np.clip(y0, 0, h - 1)))

        real_x = x1 + dark_x
        real_y = y1 + dark_y
        dist_sq = (real_x - pt_x) ** 2 + (real_y - pt_y) ** 2
        min_idx = int(np.argmin(dist_sq))
        return (int(real_x[min_idx]), int(real_y[min_idx]))

    def process_page(
        self,
        image_bgr: np.ndarray,
        bubbles: List[Rect],
        text_lines: List[TextLineItem]
    ) -> List[CrunchPartitionItem]:
        partitions: List[CrunchPartitionItem] = []
        bmp_h, bmp_w = image_bgr.shape[:2]

        for b_idx, b_rect in enumerate(bubbles):
            pad = 4
            left = max(0, min(bmp_w - 1, b_rect.left - pad))
            top = max(0, min(bmp_h - 1, b_rect.top - pad))
            right = max(left + 1, min(bmp_w, b_rect.right + pad))
            bottom = max(top + 1, min(bmp_h, b_rect.bottom + pad))
            crop_w = right - left
            crop_h = bottom - top

            if crop_w < 12 or crop_h < 12:
                continue

            crop_bgr = image_bgr[top:bottom, left:right]
            pred = self.predict_crop(crop_bgr)

            if pred is None:
                partitions.append(CrunchPartitionItem(
                    bubble_index=b_idx,
                    bubble_rect=b_rect,
                    is_conjoined=False,
                    conf_conj=0.0
                ))
                continue

            p1_raw_page = (pred["p1_raw"][0] + left, pred["p1_raw"][1] + top)
            p2_raw_page = (pred["p2_raw"][0] + left, pred["p2_raw"][1] + top)
            p1_snapped_page = (pred["p1_snapped"][0] + left, pred["p1_snapped"][1] + top)
            p2_snapped_page = (pred["p2_snapped"][0] + left, pred["p2_snapped"][1] + top)
            page_candidates = [(c[0] + left, c[1] + top) for c in pred["candidates"]]

            # Bubble text lines
            bubble_lines = [
                line for line in text_lines
                if b_rect.contains(line.rect.centerX(), line.rect.centerY())
            ]

            lobe_a_line_ids: List[int] = []
            lobe_b_line_ids: List[int] = []
            split_lines: List[TextLineItem] = []

            if pred["is_conjoined"]:
                p1_sep = p1_snapped_page
                p2_sep = p2_snapped_page

                dx_cut = abs(p2_sep[0] - p1_sep[0])
                dy_cut = abs(p2_sep[1] - p1_sep[1])
                is_vertical_cut = dy_cut >= dx_cut

                median_char_w = float(np.median([l.rect.width() for l in bubble_lines])) if bubble_lines else 22.0
                median_char_h = float(np.median([l.rect.height() for l in bubble_lines])) if bubble_lines else 35.0

                def d_sign(x: int, y: int) -> float:
                    return float((x - p1_sep[0]) * (p2_sep[1] - p1_sep[1]) - (y - p1_sep[1]) * (p2_sep[0] - p1_sep[0]))

                for line in bubble_lines:
                    r = line.rect
                    c1 = d_sign(r.left, r.top) >= 0.0
                    c2 = d_sign(r.right, r.top) >= 0.0
                    c3 = d_sign(r.left, r.bottom) >= 0.0
                    c4 = d_sign(r.right, r.bottom) >= 0.0
                    num_pos = sum([c1, c2, c3, c4])

                    if num_pos >= 3:
                        lobe_a_line_ids.append(line.id)
                    elif num_pos <= 1:
                        lobe_b_line_ids.append(line.id)
                    else:
                        # Straddling cutline: only slice if joined misread line
                        if is_vertical_cut:
                            is_joined_misread = r.width() >= 1.30 * median_char_w or (r.width() >= 26 and min(abs(r.left - p1_sep[0]), abs(r.right - p1_sep[0])) >= 6)
                        else:
                            is_joined_misread = r.height() >= 1.30 * median_char_h or (r.height() >= 30 and min(abs(r.top - p1_sep[1]), abs(r.bottom - p1_sep[1])) >= 6)

                        sliced = self._try_slice_straddling_line(image_bgr, line, p1_sep, p2_sep) if is_joined_misread else None
                        if sliced is not None:
                            line_a, line_b = sliced
                            split_lines.extend([line_a, line_b])
                            lobe_a_line_ids.append(line_a.id)
                            lobe_b_line_ids.append(line_b.id)
                        else:
                            c_sign = d_sign(r.centerX(), r.centerY())
                            if c_sign >= 0.0:
                                lobe_a_line_ids.append(line.id)
                            else:
                                lobe_b_line_ids.append(line.id)
            else:
                lobe_a_line_ids = [line.id for line in bubble_lines]

            partitions.append(CrunchPartitionItem(
                bubble_index=b_idx,
                bubble_rect=b_rect,
                is_conjoined=pred["is_conjoined"],
                conf_conj=pred["conf_conj"],
                p1_raw=p1_raw_page if pred["is_conjoined"] else None,
                p2_raw=p2_raw_page if pred["is_conjoined"] else None,
                p1_snapped=p1_snapped_page if pred["is_conjoined"] else None,
                p2_snapped=p2_snapped_page if pred["is_conjoined"] else None,
                candidate_points=page_candidates,
                lobe_a_line_ids=lobe_a_line_ids,
                lobe_b_line_ids=lobe_b_line_ids,
                split_lines=split_lines
            ))

        return partitions

    def _try_slice_straddling_line(
        self,
        image_bgr: np.ndarray,
        line: TextLineItem,
        p1: Tuple[int, int],
        p2: Tuple[int, int]
    ) -> Optional[Tuple[TextLineItem, TextLineItem]]:
        r = line.rect
        bmp_h, bmp_w = image_bgr.shape[:2]
        r_l = max(0, min(bmp_w - 1, r.left))
        r_t = max(0, min(bmp_h - 1, r.top))
        r_r = max(r_l + 1, min(bmp_w, r.right))
        r_b = max(r_t + 1, min(bmp_h, r.bottom))
        w = r_r - r_l
        h = r_b - r_t
        if w < 12 or h < 12:
            return None

        dy = abs(p2[1] - p1[1])
        dx = abs(p2[0] - p1[0])
        is_vertical_cut = dy >= dx

        patch = image_bgr[r_t:r_b, r_l:r_r]
        gray = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY)
        ink = (gray < 140).astype(np.float32)

        if is_vertical_cut:
            y_mid = (r_t + r_b) / 2.0
            p_dy = float(p2[1] - p1[1])
            if abs(p_dy) > 0.001:
                t = (y_mid - p1[1]) / p_dy
                x_cut = int(round(p1[0] + t * (p2[0] - p1[0])))
            else:
                x_cut = (p1[0] + p2[0]) // 2

            rel_cut_x = x_cut - r_l
            win = max(4, int(w * 0.35))
            x_start = max(4, min(w - 5, rel_cut_x - win))
            x_end = max(x_start + 1, min(w - 4, rel_cut_x + win))
            if x_start >= x_end:
                return None

            col_ink = np.sum(ink[:, x_start:x_end], axis=0)
            valley_rel = int(np.argmin(col_ink))
            best_cut_x = r_l + x_start + valley_rel

            rect_a = Rect(r_l, r_t, best_cut_x - 1, r_b)
            rect_b = Rect(best_cut_x + 1, r_t, r_r, r_b)
        else:
            x_mid = (r_l + r_r) / 2.0
            p_dx = float(p2[0] - p1[0])
            if abs(p_dx) > 0.001:
                t = (x_mid - p1[0]) / p_dx
                y_cut = int(round(p1[1] + t * (p2[1] - p1[1])))
            else:
                y_cut = (p1[1] + p2[1]) // 2

            rel_cut_y = y_cut - r_t
            win = max(4, int(h * 0.35))
            y_start = max(4, min(h - 5, rel_cut_y - win))
            y_end = max(y_start + 1, min(h - 4, rel_cut_y + win))
            if y_start >= y_end:
                return None

            row_ink = np.sum(ink[y_start:y_end, :], axis=1)
            valley_rel = int(np.argmin(row_ink))
            best_cut_y = r_t + y_start + valley_rel

            rect_a = Rect(r_l, r_t, r_r, best_cut_y - 1)
            rect_b = Rect(r_l, best_cut_y + 1, r_r, r_b)

        if rect_a.width() >= 6 and rect_a.height() >= 6 and rect_b.width() >= 6 and rect_b.height() >= 6:
            line_a = TextLineItem(id=line.id * 100 + 1, rect=rect_a, category=line.category)
            line_b = TextLineItem(id=line.id * 100 + 2, rect=rect_b, category=line.category)
            return line_a, line_b

        return None
