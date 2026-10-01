import cv2
import numpy as np
from typing import List, Tuple, Optional
from collections import deque
from .types import Rect, TextLineItem, BubbleMask

class TextCategorizer:
    """
    1:1 Port of TextCategorizer.kt (Module 1.5).
    Classifies raw character boxes into BUBBLED, ORPHAN, and SFX.
    Uses Supreme Zero-OCR Classifier (combining stroke caliber/weight and geometric aspect regularity).
    """

    @staticmethod
    def categorize_boxes(
        raw_boxes: List[Rect],
        bubble_regions: List[Rect],
        bubble_masks: List[BubbleMask],
        image_bgr: Optional[np.ndarray],
        bitmap_width: int,
        bitmap_height: int,
        ctd_bubbles: Optional[List[Rect]] = None,
        is_rtl: bool = True
    ) -> Tuple[List[TextLineItem], List[TextLineItem], List[TextLineItem], List[TextLineItem], List[Rect]]:
        """
        Returns (all_boxes, bubbled_boxes, orphan_boxes, sfx_boxes, distinct_bubbles).
        """
        if not raw_boxes:
            return [], [], [], [], []

        bubbled = []
        orphan = []
        sfx = []
        assigned = [False] * len(raw_boxes)

        # 1. Filter degenerate bubble regions and combine (Manga109 masks + CTD bubble envelopes)
        valid_bubble_regions = [b for b in bubble_regions if b.width() >= 18 and b.height() >= 22 and b.width() * b.height() >= 400]
        valid_masks = [bm for bm in bubble_masks if bm.width >= 18 and bm.height >= 22 and bm.width * bm.height >= 400]
        distinct_bubbles: List[Rect] = []
        if valid_masks:
            for bm in valid_masks:
                if bm.is_lobe:
                    distinct_bubbles.append(bm.rect.copy())
                    continue
                dup = False
                for existing in distinct_bubbles:
                    il = max(existing.left, bm.rect.left)
                    it = max(existing.top, bm.rect.top)
                    ir = min(existing.right, bm.rect.right)
                    ib = min(existing.bottom, bm.rect.bottom)
                    if ir > il and ib > it:
                        ia = (ir - il) * (ib - it)
                        union_a = existing.width() * existing.height() + bm.rect.width() * bm.rect.height() - ia
                        if union_a > 0 and (ia / float(union_a)) > 0.70:
                            dup = True
                            break
                if not dup:
                    distinct_bubbles.append(bm.rect.copy())

        else:
            for b in valid_bubble_regions:
                existing = None
                for other in distinct_bubbles:
                    il = max(other.left, b.left)
                    it = max(other.top, b.top)
                    ir = min(other.right, b.right)
                    ib = min(other.bottom, b.bottom)
                    if ir > il and ib > it:
                        ia = (ir - il) * (ib - it)
                        min_a = min(other.width() * other.height(), b.width() * b.height())
                        if min_a > 0 and (ia / float(min_a)) > 0.65:
                            existing = other
                            break
                if existing is not None:
                    existing.left = min(existing.left, b.left)
                    existing.top = min(existing.top, b.top)
                    existing.right = max(existing.right, b.right)
                    existing.bottom = max(existing.bottom, b.bottom)
                else:
                    distinct_bubbles.append(b.copy())

        # 2. Classify inside speech bubbles (Max-Intersection Association)
        bubble_assignments = [[] for _ in range(len(distinct_bubbles))]

        for idx, box in enumerate(raw_boxes):
            cx = box.centerX()
            cy = box.centerY()
            box_area = box.width() * box.height()
            best_b_idx = None
            max_inter_area = 0

            for b_idx, bubble in enumerate(distinct_bubbles):
                inter_l = max(box.left, bubble.left)
                inter_t = max(box.top, bubble.top)
                inter_r = min(box.right, bubble.right)
                inter_b = min(box.bottom, bubble.bottom)
                if inter_r > inter_l and inter_b > inter_t:
                    inter_area = (inter_r - inter_l) * (inter_b - inter_t)
                    ratio = (inter_area / float(box_area)) if box_area > 0 else 0.0
                    excess_x = max(0, box.right - bubble.right) + max(0, bubble.left - box.left)
                    is_truly_in = (ratio >= 0.60 and excess_x <= max(12, int(box.width() * 0.25))) or (ratio >= 0.85)
                    in_envelope = (bubble.left - 4 <= cx <= bubble.right + 4) and (bubble.top - 4 <= cy <= bubble.bottom + 4)

                    is_in_bubble_envelope = (
                        (bubble.contains(cx, cy) and (ratio >= 0.45 or is_truly_in)) or
                        (in_envelope and (ratio >= 0.70))
                    )

                    matching_mask = next((bm for bm in valid_masks if bm.rect == bubble), None)
                    if matching_mask is None:
                        max_inter = 0
                        for bm in valid_masks:
                            mil = max(bm.rect.left, bubble.left)
                            mit = max(bm.rect.top, bubble.top)
                            mir = min(bm.rect.right, bubble.right)
                            mib = min(bm.rect.bottom, bubble.bottom)
                            if mir > mil and mib > mit:
                                inter = (mir - mil) * (mib - mit)
                                if inter > max_inter:
                                    max_inter = inter
                                    matching_mask = bm
                    if matching_mask is not None:
                        mil = max(matching_mask.rect.left, box.left)
                        mit = max(matching_mask.rect.top, box.top)
                        mir = min(matching_mask.rect.right, box.right)
                        mib = min(matching_mask.rect.bottom, box.bottom)
                        mask_ratio = 0.0
                        if mir > mil and mib > mit:
                            sub_m = matching_mask.mask[mit - matching_mask.rect.top:mib - matching_mask.rect.top,
                                                       mil - matching_mask.rect.left:mir - matching_mask.rect.left]
                            mask_ratio = np.sum(sub_m) / float(box_area) if box_area > 0 else 0.0
                        lx = cx - matching_mask.rect.left
                        ly = cy - matching_mask.rect.top
                        in_mask = False
                        if 0 <= lx < matching_mask.width and 0 <= ly < matching_mask.height:
                            in_mask = bool(matching_mask.mask[ly, lx])
                        if in_mask and in_envelope and mask_ratio >= 0.45:
                            if inter_area > max_inter_area:
                                max_inter_area = inter_area
                                best_b_idx = b_idx
                    else:
                        if is_in_bubble_envelope and inter_area > max_inter_area:
                            max_inter_area = inter_area
                            best_b_idx = b_idx

            if best_b_idx is not None:
                assigned[idx] = True
                bubble_assignments[best_b_idx].append(idx)

        for b_idx, indices in enumerate(bubble_assignments):
            for idx in indices:
                box = raw_boxes[idx]
                box_id = box.id if box.id is not None else (idx + 1)
                bubbled.append(TextLineItem(id=box_id, rect=box, category="BUBBLED"))

        # 3. Classify non-bubbled boxes outside speech bubbles
        orphan_indices = [i for i in range(len(raw_boxes)) if not assigned[i]]
        raw_non_bubbled = [raw_boxes[i] for i in orphan_indices]

        non_bubbled_clusters = TextCategorizer._cluster_adjacent_boxes(raw_non_bubbled)
        valid_ctd = [b for b in ctd_bubbles if b.width() >= 14 and b.height() >= 14] if ctd_bubbles else []

        for cluster in non_bubbled_clusters:
            if valid_ctd:
                matches_ctd = False
                for box in cluster:
                    cx = box.centerX()
                    cy = box.centerY()
                    box_area = box.width() * box.height()
                    for ctd in valid_ctd:
                        il = max(box.left, ctd.left)
                        it = max(box.top, ctd.top)
                        ir = min(box.right, ctd.right)
                        ib = min(box.bottom, ctd.bottom)
                        inter_a = (ir - il) * (ib - it) if (ir > il and ib > it) else 0
                        ratio = (inter_a / float(box_area)) if box_area > 0 else 0.0
                        in_env = (ctd.left - 6 <= cx <= ctd.right + 6) and (ctd.top - 6 <= cy <= ctd.bottom + 6)
                        if (ctd.contains(cx, cy) and ratio >= 0.35) or (in_env and ratio >= 0.40) or (ratio >= 0.55):
                            matches_ctd = True
                            break
                    if matches_ctd:
                        break
                cluster_cat = "ORPHAN" if matches_ctd else "SFX"
            else:
                sfx_votes = 0
                if image_bgr is not None:
                    for b in cluster:
                        cat = TextCategorizer._classify_orphan_vs_sfx(image_bgr, b, cluster)
                        if cat == "SFX":
                            sfx_votes += 1
                    cluster_cat = "SFX" if (sfx_votes / float(len(cluster)) >= 0.65) else "ORPHAN"
                else:
                    cluster_cat = "ORPHAN"

            for b in cluster:
                box_id = b.id if b.id is not None else (raw_boxes.index(b) + 1 if b in raw_boxes else 0)
                item = TextLineItem(id=box_id, rect=b, category=cluster_cat)
                if cluster_cat == "ORPHAN":
                    orphan.append(item)
                else:
                    sfx.append(item)

        # 4. RTL Sort: vertical bands (centerY // 200), right-to-left (-centerX)
        all_combined = bubbled + orphan + sfx
        if is_rtl:
            all_combined.sort(key=lambda it: (it.rect.centerY() // 200, -it.rect.centerX()))
        else:
            all_combined.sort(key=lambda it: (it.rect.centerY() // 200, it.rect.centerX()))

        for idx, item in enumerate(all_combined):
            if item.id == 0 or item.id is None:
                item.id = idx + 1

        b_boxes = [it for it in all_combined if it.category == "BUBBLED"]
        o_boxes = [it for it in all_combined if it.category == "ORPHAN"]
        s_boxes = [it for it in all_combined if it.category == "SFX"]

        return all_combined, b_boxes, o_boxes, s_boxes, distinct_bubbles

    @staticmethod
    def _classify_orphan_vs_sfx(image_bgr: np.ndarray, rect: Rect, cluster: List[Rect]) -> str:
        h_img, w_img = image_bgr.shape[:2]
        cl_l = max(0, min(w_img, rect.left))
        cl_t = max(0, min(h_img, rect.top))
        cl_r = max(0, min(w_img, rect.right))
        cl_b = max(0, min(h_img, rect.bottom))
        w = cl_r - cl_l
        h = cl_b - cl_t
        if w < 6 or h < 6:
            return "SFX"

        patch = image_bgr[cl_t:cl_b, cl_l:cl_r]
        gray = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY)
        min_l = int(np.min(gray))
        max_l = int(np.max(gray))
        contrast = max_l - min_l
        if contrast < 28:
            return "SFX"

        mean_l = float(np.mean(gray))
        is_light = mean_l >= 120.0
        stroke_threshold = min(185, max_l - 45) if is_light else max(70, min_l + 45)

        dark_pixels = int(np.sum(gray <= stroke_threshold if is_light else gray >= stroke_threshold))
        total_pixels = w * h
        fill_ratio = dark_pixels / float(total_pixels)

        diff_x = np.abs(gray[:, :-1].astype(np.int32) - gray[:, 1:].astype(np.int32))
        diff_y = np.abs(gray[:-1, :].astype(np.int32) - gray[1:, :].astype(np.int32))
        edge_pixels = int(np.sum((diff_x[:-1, :] + diff_y[:, :-1]) > 50))

        stroke_caliber = (2.0 * dark_pixels) / float(max(1, edge_pixels))

        if 1.0 <= stroke_caliber <= 4.8 and 0.05 <= fill_ratio <= 0.75:
            stroke_score = 1.0
        elif 0.8 <= stroke_caliber <= 5.8 and 0.04 <= fill_ratio <= 0.85:
            stroke_score = 0.70
        elif stroke_caliber > 8.0 or fill_ratio < 0.02:
            stroke_score = 0.1
        else:
            stroke_score = 0.45

        # Aspect regularity: Japanese glyphs aspect ~ 1.0, vertical columns aspect <= 0.55
        aspect_regularity_score = 0.6
        if cluster:
            ratios = [c.width() / float(max(1, c.height())) for c in cluster]
            avg_ratio = float(np.mean(ratios))
            if 0.10 <= avg_ratio <= 0.55:
                aspect_regularity_score = 1.0
            elif 0.65 <= avg_ratio <= 1.40:
                aspect_regularity_score = 1.0
            else:
                aspect_regularity_score = 0.45

        supreme_score = 0.55 * stroke_score + 0.45 * aspect_regularity_score
        return "ORPHAN" if supreme_score >= 0.50 else "SFX"

    @staticmethod
    def _cluster_adjacent_boxes(boxes: List[Rect]) -> List[List[Rect]]:
        if not boxes:
            return []
        visited = [False] * len(boxes)
        clusters = []

        char_widths = sorted([min(b.width(), b.height()) for b in boxes])
        median_w = float(np.clip(char_widths[len(char_widths) // 2], 16, 50))

        for i in range(len(boxes)):
            if visited[i]:
                continue
            visited[i] = True
            current_cluster = [boxes[i]]
            queue = deque([i])

            while queue:
                curr_idx = queue.popleft()
                b1 = boxes[curr_idx]

                for j in range(len(boxes)):
                    if visited[j]:
                        continue
                    b2 = boxes[j]
                    dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))
                    dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))
                    cx_dist = abs(b1.centerX() - b2.centerX())
                    v_overlap = max(0, min(b1.bottom, b2.bottom) - max(b1.top, b2.top))
                    min_h = min(b1.height(), b2.height())

                    is_col = (cx_dist <= 1.25 * median_w) and (dy <= 2.8 * median_w)
                    is_parallel = (dx <= 2.2 * median_w) and (v_overlap >= 0.15 * min_h or dy <= 1.0 * median_w)

                    if is_col or is_parallel:
                        visited[j] = True
                        current_cluster.append(b2)
                        queue.append(j)

            clusters.append(current_cluster)

        return clusters
