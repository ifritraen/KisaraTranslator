import cv2
import numpy as np
from typing import List, Tuple, Optional, Dict
from .types import Rect

class ComicTextDetector:
    """
    1:1 Port of ComicTextDetector.kt for PC Simulator.
    High-performance, thermal-safe inference using OpenCV DNN backend with
    Zenkaku Square Em-Box Radical & Stroke Recombiner and Heatmap Island Recovery.
    """
    TARGET_DIM = 1024

    def __init__(self, model_path: str):
        self.net = cv2.dnn.readNetFromONNX(model_path)
        cv2.setNumThreads(2)

    def run_inference(self, image_bgr: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        """
        Runs model inference on image scaled to 1024x1024 planar RGB [0..1].
        Returns (blk, seg):
          blk: [64512, 7]
          seg: [1024, 1024] float map [0..1]
        """
        resized = cv2.resize(image_bgr, (self.TARGET_DIM, self.TARGET_DIM), interpolation=cv2.INTER_LINEAR)
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32) * (1.0 / 255.0)
        blob = np.transpose(rgb, (2, 0, 1))[np.newaxis, ...]  # [1, 3, 1024, 1024]

        self.net.setInput(blob)
        outputs = self.net.forward(['blk', 'seg', 'det'])
        blk = outputs[0][0]
        seg = outputs[1][0, 0]
        return blk, seg

    def detect_pass1_and_bubbles(
        self,
        image_bgr: np.ndarray,
        blk: np.ndarray,
        seg: np.ndarray,
        mask_threshold: float = 0.30,
        nms_iou_threshold: float = 0.35
    ) -> Tuple[List[Rect], List[Rect]]:
        orig_h, orig_w = image_bgr.shape[:2]
        scale_x = orig_w / float(self.TARGET_DIM)
        scale_y = orig_h / float(self.TARGET_DIM)

        # 1. Speech bubble envelopes from blk
        cand_boxes = []
        cand_scores = []
        conf_mask = blk[:, 4] > 0.20
        cand_indices = np.where(conf_mask)[0]

        for k in cand_indices:
            cx, cy, bw, bh, conf = blk[k, 0:5]
            x1 = max(0, int((cx - bw / 2.0) * scale_x))
            y1 = max(0, int((cy - bh / 2.0) * scale_y))
            x2 = min(orig_w, int((cx + bw / 2.0) * scale_x))
            y2 = min(orig_h, int((cy + bh / 2.0) * scale_y))

            if x2 > x1 + 10 and y2 > y1 + 10:
                cand_boxes.append(Rect(x1, y1, x2, y2))
                cand_scores.append(float(conf))

        # Greedy NMS
        sorted_order = sorted(range(len(cand_scores)), key=lambda i: cand_scores[i], reverse=True)
        kept_indices = []
        for idx in sorted_order:
            box = cand_boxes[idx]
            suppress = False
            for kept in kept_indices:
                kept_box = cand_boxes[kept]
                inter_l = max(box.left, kept_box.left)
                inter_t = max(box.top, kept_box.top)
                inter_r = min(box.right, kept_box.right)
                inter_b = min(box.bottom, kept_box.bottom)
                if inter_r > inter_l and inter_b > inter_t:
                    inter_area = (inter_r - inter_l) * (inter_b - inter_t)
                    union_area = box.width() * box.height() + kept_box.width() * kept_box.height() - inter_area
                    if union_area > 0 and (inter_area / union_area) > nms_iou_threshold:
                        suppress = True
                        break
            if not suppress:
                kept_indices.append(idx)

        bubbles = [cand_boxes[i] for i in kept_indices]

        # 2. Extract character/word bounding boxes from seg via 4x4 grid clustering
        grid_step = 4
        seg_grid = (seg[grid_step // 2::grid_step, grid_step // 2::grid_step] >= mask_threshold).astype(np.uint8)
        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(seg_grid, connectivity=4)

        raw_boxes = []
        pad_x = int(3 * scale_x)
        pad_y = int(3 * scale_y)

        for i in range(1, num_labels):
            count = stats[i, cv2.CC_STAT_AREA]
            if count >= 3:
                gx = stats[i, cv2.CC_STAT_LEFT]
                gy = stats[i, cv2.CC_STAT_TOP]
                gw = stats[i, cv2.CC_STAT_WIDTH]
                gh = stats[i, cv2.CC_STAT_HEIGHT]

                min_px = gx * grid_step + grid_step // 2
                min_py = gy * grid_step + grid_step // 2
                max_px = (gx + gw - 1) * grid_step + grid_step // 2 + grid_step
                max_py = (gy + gh - 1) * grid_step + grid_step // 2 + grid_step

                rect = Rect(
                    max(0, int(min_px * scale_x - pad_x)),
                    max(0, int(min_py * scale_y - pad_y)),
                    min(orig_w, int(max_px * scale_x + pad_x)),
                    min(orig_h, int(max_py * scale_y + pad_y))
                )
                if rect.width() > 10 and rect.height() > 10:
                    raw_boxes.append(rect)

        return raw_boxes, bubbles

    def extract_heatmap_text_islands(
        self,
        prob_map: np.ndarray,
        existing_boxes: List[Rect],
        bubbles: List[Rect],
        img_w: int,
        img_h: int
    ) -> List[Rect]:
        scale_x = self.TARGET_DIM / float(img_w)
        scale_y = self.TARGET_DIM / float(img_h)
        inv_scale_x = img_w / float(self.TARGET_DIM)
        inv_scale_y = img_h / float(self.TARGET_DIM)

        bubble_mask = np.zeros((self.TARGET_DIM, self.TARGET_DIM), dtype=bool)
        for b in bubbles:
            bx1 = max(0, min(self.TARGET_DIM - 1, int(b.left * scale_x)))
            by1 = max(0, min(self.TARGET_DIM - 1, int(b.top * scale_y)))
            bx2 = max(0, min(self.TARGET_DIM - 1, int(b.right * scale_x)))
            by2 = max(0, min(self.TARGET_DIM - 1, int(b.bottom * scale_y)))
            if bx2 >= bx1 and by2 >= by1:
                bubble_mask[by1:by2 + 1, bx1:bx2 + 1] = True

        threshold = np.where(bubble_mask, 0.18, 0.22)
        unclaimed = (prob_map >= threshold).astype(np.uint8)

        halo_x = max(3, int(4 * scale_x))
        halo_y = max(3, int(4 * scale_y))
        for b in existing_boxes:
            bx1 = max(0, min(self.TARGET_DIM - 1, int(b.left * scale_x) - halo_x))
            by1 = max(0, min(self.TARGET_DIM - 1, int(b.top * scale_y) - halo_y))
            bx2 = max(0, min(self.TARGET_DIM - 1, int(b.right * scale_x) + halo_x))
            by2 = max(0, min(self.TARGET_DIM - 1, int(b.bottom * scale_y) + halo_y))
            if bx2 >= bx1 and by2 >= by1:
                unclaimed[by1:by2 + 1, bx1:bx2 + 1] = 0

        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(unclaimed, connectivity=4)
        candidate_boxes = []

        pad_x = max(2, int(3 * inv_scale_x))
        pad_y = max(2, int(3 * inv_scale_y))

        for i in range(1, num_labels):
            count = stats[i, cv2.CC_STAT_AREA]
            gx = stats[i, cv2.CC_STAT_LEFT]
            gy = stats[i, cv2.CC_STAT_TOP]
            gw = stats[i, cv2.CC_STAT_WIDTH]
            gh = stats[i, cv2.CC_STAT_HEIGHT]

            if gw >= 750 or gh >= 750:
                continue

            sub_mask = (labels[gy:gy+gh, gx:gx+gw] == i)
            sub_prob = prob_map[gy:gy+gh, gx:gx+gw]
            peak = float(np.max(sub_prob[sub_mask]))

            mid_x = gx + gw // 2
            mid_y = gy + gh // 2
            is_inside_bubble = bubble_mask[mid_y, mid_x]

            is_valid = (count >= 3 or (count >= 2 and peak >= 0.22)) if is_inside_bubble else (count >= 5 or (count >= 3 and peak >= 0.28))
            if not is_valid:
                continue

            cand_rect = Rect(
                max(0, int(gx * inv_scale_x - pad_x)),
                max(0, int(gy * inv_scale_y - pad_y)),
                min(img_w, int((gx + gw) * inv_scale_x + pad_x)),
                min(img_h, int((gy + gh) * inv_scale_y + pad_y))
            )

            # Overlap check with existing boxes
            overlaps = False
            for ex in existing_boxes:
                il = max(ex.left, cand_rect.left)
                it = max(ex.top, cand_rect.top)
                ir = min(ex.right, cand_rect.right)
                ib = min(ex.bottom, cand_rect.bottom)
                if ir > il and ib > it:
                    iarea = (ir - il) * (ib - it)
                    carea = cand_rect.width() * cand_rect.height()
                    earea = ex.width() * ex.height()
                    if carea > 0 and earea > 0:
                        if (iarea / carea > 0.20) or (iarea / earea > 0.35):
                            overlaps = True
                            break
                else:
                    tl = max(ex.left - 2, cand_rect.left)
                    tt = max(ex.top - 2, cand_rect.top)
                    tr = min(ex.right + 2, cand_rect.right)
                    tb = min(ex.bottom + 2, cand_rect.bottom)
                    if tr > tl and tb > tt and (cand_rect.width() <= 12 or cand_rect.height() <= 12):
                        overlaps = True
                        break

            min_dim = 6 if is_inside_bubble else 8
            if not overlaps and cand_rect.width() >= min_dim and cand_rect.height() >= min_dim:
                candidate_boxes.append((cand_rect, peak))

        candidate_boxes.sort(key=lambda item: item[1], reverse=True)
        return [c[0] for c in candidate_boxes]

    def deduplicate_boxes(self, boxes: List[Rect]) -> List[Rect]:
        result: List[Rect] = []
        for box in boxes:
            exists = False
            for ex in result:
                il = max(ex.left, box.left)
                it = max(ex.top, box.top)
                ir = min(ex.right, box.right)
                ib = min(ex.bottom, box.bottom)
                if ir > il and ib > it:
                    iarea = (ir - il) * (ib - it)
                    min_area = min(ex.width() * ex.height(), box.width() * box.height())
                    if min_area > 0 and (iarea / float(min_area)) > 0.35:
                        exists = True
                        break
            if not exists:
                result.append(box)
        return result

    def recombine_split_character_boxes(self, boxes: List[Rect], bubbles: List[Rect]) -> List[Rect]:
        """
        Zenkaku Square Em-Box Radical & Stroke Recombiner.
        1:1 Port of recombineSplitCharacterBoxes from ComicTextDetector.kt.
        """
        if len(boxes) < 2:
            return boxes

        active = [b.copy() for b in boxes]

        vert_w = sorted([b.width() for b in active if b.height() >= b.width()])
        if vert_w:
            global_char_w = float(np.clip(vert_w[len(vert_w) // 2], 16.0, 44.0))
        else:
            all_w = sorted([b.width() for b in active])
            global_char_w = float(np.clip(all_w[len(all_w) // 2], 16.0, 44.0)) if all_w else 24.0

        bubble_char_w: Dict[int, float] = {}
        for b_idx, bubble in enumerate(bubbles):
            in_bubble = [b for b in active if bubble.contains(b.centerX(), b.centerY())]
            in_vert_w = sorted([b.width() for b in in_bubble if b.height() >= b.width()])
            if in_vert_w:
                bubble_char_w[b_idx] = float(np.clip(in_vert_w[len(in_vert_w) // 2], 16.0, 44.0))
            else:
                in_all_w = sorted([b.width() for b in in_bubble])
                bubble_char_w[b_idx] = float(np.clip(in_all_w[len(in_all_w) // 2], 16.0, 44.0)) if in_all_w else global_char_w

        changed = True
        iteration = 0
        while changed and iteration < 8:
            changed = False
            iteration += 1

            for i in range(len(active)):
                a = active[i]
                for j in range(i + 1, len(active)):
                    b = active[j]

                    bubble_a = next((idx for idx, bub in enumerate(bubbles) if bub.contains(a.centerX(), a.centerY())), None)
                    bubble_b = next((idx for idx, bub in enumerate(bubbles) if bub.contains(b.centerX(), b.centerY())), None)
                    if bubble_a is not None and bubble_b is not None and bubble_a != bubble_b:
                        continue

                    b_idx = bubble_a if bubble_a is not None else bubble_b
                    char_w = bubble_char_w.get(b_idx, global_char_w)

                    union_l = min(a.left, b.left)
                    union_t = min(a.top, b.top)
                    union_r = max(a.right, b.right)
                    union_b = max(a.bottom, b.bottom)
                    union_w = union_r - union_l
                    union_h = union_b - union_t

                    inter_l = max(a.left, b.left)
                    inter_t = max(a.top, b.top)
                    inter_r = min(a.right, b.right)
                    inter_b = min(a.bottom, b.bottom)
                    inter_w = max(0, inter_r - inter_l)
                    inter_h = max(0, inter_b - inter_t)
                    inter_area = inter_w * inter_h

                    min_area = min(a.width() * a.height(), b.width() * b.height())

                    should_merge = False

                    # 1. Enclosure / High Overlap / Boundary Halo Touch
                    if min_area > 0 and (inter_area / float(min_area)) >= 0.35:
                        should_merge = True
                    else:
                        dil_a = Rect(a.left - 4, a.top - 4, a.right + 4, a.bottom + 4)
                        dil_b = Rect(b.left - 4, b.top - 4, b.right + 4, b.bottom + 4)
                        if dil_a.contains_rect(b) or dil_b.contains_rect(a):
                            should_merge = True

                    # 2. Horizontal Radical Split
                    if not should_merge:
                        min_h = min(a.height(), b.height())
                        v_overlap = inter_h / float(max(1, min_h))
                        h_dist = max(0, max(a.left - b.right, b.left - a.right))
                        max_allowed_h_dist = max(6, int(char_w * 0.22))

                        if v_overlap >= 0.40 and h_dist <= max_allowed_h_dist:
                            min_w = min(a.width(), b.width())
                            max_w = max(a.width(), b.width())
                            is_radical_split = ((a.width() <= char_w * 0.85 and b.width() <= char_w * 0.85) or
                                                (min_w <= char_w * 0.60 and max_w <= char_w * 1.15))
                            fits_em_box_w = union_w <= (char_w * 1.25)
                            fits_em_box_h = union_h <= max(a.height(), b.height()) + int(char_w * 0.30)
                            if is_radical_split and fits_em_box_w and fits_em_box_h:
                                should_merge = True

                    # 3. Vertical Radical / Stroke Split
                    if not should_merge:
                        min_w = min(a.width(), b.width())
                        h_overlap = inter_w / float(max(1, min_w))
                        cx_dist = abs(a.centerX() - b.centerX())
                        v_dist = max(0, max(a.top - b.bottom, b.top - a.bottom))
                        max_allowed_v_dist = max(6, int(char_w * 0.25))

                        is_vert_aligned = h_overlap >= 0.40 or cx_dist <= (char_w * 0.35)
                        if is_vert_aligned and v_dist <= max_allowed_v_dist:
                            fits_em_box_h = union_h <= (char_w * 1.28)
                            fits_em_box_w = union_w <= (char_w * 1.25)
                            if fits_em_box_h and fits_em_box_w:
                                should_merge = True

                    if should_merge:
                        a.set(union_l, union_t, union_r, union_b)
                        active.pop(j)
                        changed = True
                        break
                if changed:
                    break

        return active
    def run_module1(self, image_bgr: np.ndarray) -> Tuple[List[Rect], List[Rect], np.ndarray]:
        h_img, w_img = image_bgr.shape[:2]
        blk, seg = self.run_inference(image_bgr)
        raw_pass1, bubbles = self.detect_pass1_and_bubbles(image_bgr, blk, seg)

        heatmap_islands = self.extract_heatmap_text_islands(seg, raw_pass1, bubbles, w_img, h_img)
        pass1_boxes = self.recombine_split_character_boxes(
            self.deduplicate_boxes(raw_pass1 + heatmap_islands), bubbles
        )
        for idx, box in enumerate(pass1_boxes):
            box.id = idx + 1

        return pass1_boxes, bubbles, seg
