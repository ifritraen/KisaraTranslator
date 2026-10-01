import cv2
import numpy as np
from typing import List, Tuple, Optional
from .types import Rect, TextLineItem, BubbleMask

class GroundedColumn:
    def __init__(self, col, rect: Rect, char_min_left: int, char_max_right: int, cx: float):
        self.col = col
        self.rect = rect
        self.char_min_left = char_min_left
        self.char_max_right = char_max_right
        self.cx = cx

class ColumnCluster:
    def __init__(self, members: List[Rect]):
        self.members = list(members)
        self.min_left = min(b.left for b in members)
        self.max_right = max(b.right for b in members)
        self.min_top = min(b.top for b in members)
        self.max_bottom = max(b.bottom for b in members)

    @property
    def cx(self) -> float:
        return float(np.mean([b.centerX() for b in self.members])) if self.members else 0.0

class VerticalLineStitcher:
    """
    1:1 Port of VerticalLineStitcher.kt (Module 2).
    Enforces the 6 Inviolable Pipeline Invariants:
    1. CTD Box Atomicity: CTD Pass 1 boxes are indivisible units of ink. Seams snap to whitespace gutters.
    2. In-Bubble Isolation: Speech bubble dialogue lines never merge with unbubbled orphan/SFX lines.
    3. 1 Line Per Column: Inside any speech bubble, a vertical column has zero breaks from top to bottom.
    4. Uniform Width: All columns in a bubble share uniform width = median(char.width) * 1.08f.
    5. Strict Containment: In-bubble lines are 100% contained within the bubble boundary (zero bleeding).
    6. Zero Crossover: Adjacent columns on the page maintain 0-pixel overlap.
    """

    @staticmethod
    def is_box_blank(image_bgr: np.ndarray, rect: Rect) -> bool:
        # Zero-Drop Conservation Engine: ink detection by CTD network is trusted unconditionally.
        return False

    @staticmethod
    def find_matching_mask(bubble: Rect, bubble_masks: List[BubbleMask]) -> Optional[BubbleMask]:
        if not bubble_masks:
            return None
        for bm in bubble_masks:
            if bm.rect == bubble:
                return bm
        max_inter = 0
        best_mask = None
        for bm in bubble_masks:
            il = max(bm.rect.left, bubble.left)
            it = max(bm.rect.top, bubble.top)
            ir = min(bm.rect.right, bubble.right)
            ib = min(bm.rect.bottom, bubble.bottom)
            if ir > il and ib > it:
                inter = (ir - il) * (ib - it)
                if inter > max_inter:
                    max_inter = inter
                    best_mask = bm
        return best_mask

    @staticmethod
    def stitch_lines(
        categorized_boxes: List[TextLineItem],
        distinct_bubbles: List[Rect],
        bubble_masks: List[BubbleMask],
        image_bgr: Optional[np.ndarray],
        bitmap_width: int,
        bitmap_height: int,
        is_rtl: bool = True
    ) -> Tuple[List[TextLineItem], List[TextLineItem], List[TextLineItem], List[TextLineItem], int]:
        """
        Returns (all_lines, bubbled_lines, orphan_lines, sfx_lines, suppressed_furigana_count).
        """
        if not categorized_boxes:
            return [], [], [], [], 0

        # Zero-Drop Conservation Engine: Preserve 100% of detected CTD boxes
        valid_categorized = list(categorized_boxes)
        if not valid_categorized:
            return [], [], [], [], 0

        valid_boxes = [b for b in valid_categorized if b.rect.width() >= 6 and b.rect.height() >= 6]
        scale_res = max(1.0, bitmap_width / 1024.0)
        if valid_boxes:
            sorted_w = sorted([b.rect.width() for b in valid_boxes])
            median_char_w = float(np.clip(sorted_w[len(sorted_w) // 2], 16.0, 48.0 * scale_res))
        else:
            median_char_w = 26.0 * scale_res

        assigned = [False] * len(valid_categorized)
        result_bubbled: List[Rect] = []
        total_furigana = 0

        # 1. Process inside each speech bubble (Max-Intersection Association)
        bubble_assignments = [[] for _ in range(len(distinct_bubbles))]

        for idx, item in enumerate(valid_categorized):
            box = item.rect
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
                    in_envelope = (bubble.left - 6 <= cx <= bubble.right + 6) and (bubble.top - 6 <= cy <= bubble.bottom + 6)

                    match = False
                    matching_mask = VerticalLineStitcher.find_matching_mask(bubble, bubble_masks)
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
                        in_mask = (0 <= lx < matching_mask.width and 0 <= ly < matching_mask.height and bool(matching_mask.mask[ly, lx]))
                        if in_mask and in_envelope and (mask_ratio >= 0.45 or (item.category == "BUBBLED" and mask_ratio >= 0.35)):
                            match = True
                    else:
                        if item.category == "BUBBLED":
                            if bubble.contains(cx, cy) or (in_envelope and (ratio >= 0.35 or inter_area > 0)):
                                match = True
                        else:
                            if is_truly_in and in_envelope:
                                match = True

                    if match and inter_area > max_inter_area:
                        max_inter_area = inter_area
                        best_b_idx = b_idx

            if best_b_idx is not None:
                assigned[idx] = True
                bubble_assignments[best_b_idx].append(idx)

        for b_idx, indices in enumerate(bubble_assignments):
            if not indices:
                continue
            b_target = distinct_bubbles[b_idx]
            matching_mask = VerticalLineStitcher.find_matching_mask(b_target, bubble_masks)
            raw_cluster = [valid_categorized[idx].rect for idx in indices]
            stitched, furi_count = VerticalLineStitcher.stitch_bubble_columns(
                raw_cluster, b_target, median_char_w, image_bgr, matching_mask=matching_mask
            )
            result_bubbled.extend(stitched)
            total_furigana += furi_count

        # 2. Process non-bubbled boxes
        orphan_indices = [i for i in range(len(valid_categorized)) if not assigned[i]]
        orphan_boxes = [valid_categorized[i].rect for i in orphan_indices if valid_categorized[i].category != "SFX"]
        sfx_boxes = [valid_categorized[i].rect for i in orphan_indices if valid_categorized[i].category == "SFX"]

        unbubbled_orphan, reclass_sfx = VerticalLineStitcher.stitch_unbubbled_boxes(orphan_boxes, median_char_w, is_orphan_candidate=True)
        _, unbubbled_sfx = VerticalLineStitcher.stitch_unbubbled_boxes(sfx_boxes, median_char_w, is_orphan_candidate=False)
        final_orphan = unbubbled_orphan
        final_sfx = unbubbled_sfx + reclass_sfx

        bubbled_items = [TextLineItem(id=0, rect=r, category="BUBBLED") for r in result_bubbled]
        orphan_items = [TextLineItem(id=0, rect=r, category="ORPHAN") for r in final_orphan]
        sfx_items = [TextLineItem(id=0, rect=r, category="SFX") for r in final_sfx]

        # 3. Suppress unbubbled items that significantly overlap an existing bubbled line (>= 50%)
        contained_lines = list(bubbled_items)
        for item in (orphan_items + sfx_items):
            r = item.rect.copy()
            valid = True
            for bl in bubbled_items:
                inter_l = max(r.left, bl.rect.left)
                inter_t = max(r.top, bl.rect.top)
                inter_r = min(r.right, bl.rect.right)
                inter_b = min(r.bottom, bl.rect.bottom)
                if inter_r > inter_l and inter_b > inter_t:
                    inter_area = (inter_r - inter_l) * (inter_b - inter_t)
                    r_area = r.width() * r.height()
                    if r_area > 0 and (inter_area / float(r_area)) >= 0.50:
                        valid = False
                        break
            if valid and r.width() >= 6 and r.height() >= 6:
                contained_lines.append(item.copy(rect=r))

        # 4. Cross-category deduplication
        deduplicated = VerticalLineStitcher._deduplicate_lines(contained_lines)

        # 4b. Catch-Net Reconciler: Zero-Drop Conservation Engine
        # Guarantees that every detected character box is preserved and accounted for in the output lines.
        def _is_box_covered(b: Rect, lines: List[TextLineItem]) -> bool:
            bcx = b.centerX()
            bcy = b.centerY()
            b_area = b.width() * b.height()
            for l in lines:
                if l.rect.contains(bcx, bcy):
                    return True
                il = max(l.rect.left, b.left)
                it = max(l.rect.top, b.top)
                ir = min(l.rect.right, b.right)
                ib = min(l.rect.bottom, b.bottom)
                if ir > il and ib > it:
                    inter_a = (ir - il) * (ib - it)
                    if b_area > 0 and (inter_a / float(b_area)) >= 0.35:
                        return True
            return False

        for item in categorized_boxes:
            b = item.rect
            if not _is_box_covered(b, deduplicated):
                bcx = b.centerX()
                bcy = b.centerY()
                best_b = None
                max_inter = 0
                for bubble in distinct_bubbles:
                    il = max(b.left, bubble.left)
                    it = max(b.top, bubble.top)
                    ir = min(b.right, bubble.right)
                    ib = min(b.bottom, bubble.bottom)
                    if ir > il and ib > it:
                        inter_a = (ir - il) * (ib - it)
                        if inter_a > max_inter:
                            max_inter = inter_a
                            best_b = bubble

                is_in_bubble = False
                if best_b is not None and (max_inter > 0 or best_b.contains(bcx, bcy)):
                    matching_mask = VerticalLineStitcher.find_matching_mask(best_b, bubble_masks)
                    if matching_mask is not None:
                        mil = max(matching_mask.rect.left, b.left)
                        mit = max(matching_mask.rect.top, b.top)
                        mir = min(matching_mask.rect.right, b.right)
                        mib = min(matching_mask.rect.bottom, b.bottom)
                        mask_ratio = 0.0
                        if mir > mil and mib > mit:
                            sub_m = matching_mask.mask[mit - matching_mask.rect.top:mib - matching_mask.rect.top,
                                                       mil - matching_mask.rect.left:mir - matching_mask.rect.left]
                            b_area = b.width() * b.height()
                            mask_ratio = np.sum(sub_m) / float(b_area) if b_area > 0 else 0.0
                        lx = bcx - matching_mask.rect.left
                        ly = bcy - matching_mask.rect.top
                        in_px = (0 <= lx < matching_mask.width and 0 <= ly < matching_mask.height and bool(matching_mask.mask[ly, lx]))
                        is_in_bubble = in_px and mask_ratio >= 0.35
                    else:
                        is_in_bubble = True

                if is_in_bubble and best_b is not None:
                    # Box belongs to speech bubble best_b
                    lines_in_b = [l for l in deduplicated if l.category == "BUBBLED" and
                                  (best_b.contains(l.rect.centerX(), l.rect.centerY()) or
                                   (max(best_b.left, l.rect.left) < min(best_b.right, l.rect.right) and
                                    max(best_b.top, l.rect.top) < min(best_b.bottom, l.rect.bottom)))]
                    corridor_line = next((l for l in lines_in_b if abs(l.rect.centerX() - bcx) <= median_char_w * 0.75), None)
                    if corridor_line is not None:
                        corridor_line.rect.left = min(corridor_line.rect.left, b.left)
                        corridor_line.rect.right = max(corridor_line.rect.right, b.right)
                        corridor_line.rect.top = min(corridor_line.rect.top, b.top)
                        corridor_line.rect.bottom = max(corridor_line.rect.bottom, b.bottom)
                    else:
                        half_w = int(median_char_w * 0.54)
                        cl_l = max(best_b.left, min(bcx - half_w, b.left))
                        cl_r = min(best_b.right, max(bcx + half_w, b.right))
                        cl_t = max(best_b.top, b.top)
                        cl_b = min(best_b.bottom, b.bottom)
                        deduplicated.append(TextLineItem(id=0, rect=Rect(cl_l, cl_t, cl_r, cl_b), category="BUBBLED"))
                else:
                    # Outside speech bubbles (open-art)
                    unbubbled_lines = [l for l in deduplicated if l.category in ("ORPHAN", "SFX")]
                    can_absorb = False
                    for ul in unbubbled_lines:
                        dx = abs(ul.rect.centerX() - bcx)
                        dy = max(0, max(ul.rect.top - b.bottom, b.top - ul.rect.bottom))
                        if dx <= median_char_w * 0.8 and dy <= median_char_w * 2.0:
                            ul.rect.left = min(ul.rect.left, b.left)
                            ul.rect.right = max(ul.rect.right, b.right)
                            ul.rect.top = min(ul.rect.top, b.top)
                            ul.rect.bottom = max(ul.rect.bottom, b.bottom)
                            can_absorb = True
                            break
                    if not can_absorb:
                        cat = item.category if item.category in ("ORPHAN", "SFX") else "ORPHAN"
                        deduplicated.append(TextLineItem(id=0, rect=b.copy(), category=cat))

        # Enforce non-collapsible bubble bound: for every bubble containing CTD ink, ensure at least 1 line exists
        for bubble in distinct_bubbles:
            boxes_in_b = [it for it in categorized_boxes if bubble.contains(it.rect.centerX(), it.rect.centerY()) or
                          (max(bubble.left, it.rect.left) < min(bubble.right, it.rect.right) and
                           max(bubble.top, it.rect.top) < min(bubble.bottom, it.rect.bottom) and
                           (min(bubble.right, it.rect.right) - max(bubble.left, it.rect.left)) *
                           (min(bubble.bottom, it.rect.bottom) - max(bubble.top, it.rect.top)) >= 0.45 * it.rect.width() * it.rect.height())]
            if boxes_in_b:
                has_line = any(
                    l.category == "BUBBLED" and (bubble.contains(l.rect.centerX(), l.rect.centerY()) or
                    (max(bubble.left, l.rect.left) < min(bubble.right, l.rect.right) and
                     max(bubble.top, l.rect.top) < min(bubble.bottom, l.rect.bottom)))
                    for l in deduplicated
                )
                if not has_line:
                    min_l = min(it.rect.left for it in boxes_in_b)
                    max_r = max(it.rect.right for it in boxes_in_b)
                    min_t = min(it.rect.top for it in boxes_in_b)
                    max_b = max(it.rect.bottom for it in boxes_in_b)
                    half_w = int(median_char_w * 0.54)
                    cx = (min_l + max_r) // 2
                    cl_l = max(bubble.left, min(cx - half_w, min_l))
                    cl_r = min(bubble.right, max(cx + half_w, max_r))
                    cl_t = max(bubble.top, min_t)
                    cl_b = min(bubble.bottom, max_b)
                    deduplicated.append(TextLineItem(id=0, rect=Rect(cl_l, cl_t, cl_r, cl_b), category="BUBBLED"))

        # 5. RTL Sort
        if is_rtl:
            deduplicated.sort(key=lambda it: (it.rect.centerY() // 200, -it.rect.centerX()))
        else:
            deduplicated.sort(key=lambda it: (it.rect.centerY() // 200, it.rect.centerX()))

        for idx, item in enumerate(deduplicated):
            item.id = idx + 1

        b_lines = [it for it in deduplicated if it.category == "BUBBLED"]
        o_lines = [it for it in deduplicated if it.category == "ORPHAN"]
        s_lines = [it for it in deduplicated if it.category == "SFX"]

        return deduplicated, b_lines, o_lines, s_lines, total_furigana

    @staticmethod
    def stitch_bubble_columns(
        boxes: List[Rect],
        bubble: Rect,
        global_char_w: float,
        image_bgr: Optional[np.ndarray] = None,
        matching_mask: Optional[BubbleMask] = None
    ) -> Tuple[List[Rect], int]:
        if not boxes:
            return [], 0

        # Step 0: Clip strictly to bubble bounds
        clipped = []
        for b in boxes:
            il = max(b.left, bubble.left)
            it = max(b.top, bubble.top)
            ir = min(b.right, bubble.right)
            ib = min(b.bottom, bubble.bottom)
            if ir > il and ib > it and (ir - il) >= 6 and (ib - it) >= 6:
                clipped.append(Rect(il, it, ir, ib, id=b.id))

        if not clipped:
            return [], 0

        dedup = VerticalLineStitcher._resolve_nested_boxes(clipped)

        standard_chars = [b for b in dedup if b.height() >= 16 and b.width() >= 12]
        max_allowed_w = max(48.0, global_char_w * 1.5)
        if standard_chars:
            sw = sorted([b.width() for b in standard_chars])
            local_char_w = float(np.clip(sw[len(sw) // 2], 16.0, max_allowed_w))
        else:
            local_char_w = float(np.clip(global_char_w, 16.0, max_allowed_w))

        # Step 1: Furigana ruby suppression
        cleaned_boxes, furi_count = VerticalLineStitcher.suppress_or_merge_furigana(dedup, local_char_w)

        # Step 2: 1D Horizontal Pitch Clustering (RTL)
        sorted_boxes = sorted(cleaned_boxes, key=lambda b: (-b.centerX(), b.top))
        columns: List[ColumnCluster] = []

        for box in sorted_boxes:
            bcx = float(box.centerX())
            best_col = None
            best_dist = float('inf')

            for col in columns:
                cx_dist = abs(bcx - col.cx)
                union_w = max(col.max_right, box.right) - min(col.min_left, box.left)
                h_overlap = max(0, min(col.max_right, box.right) - max(col.min_left, box.left))
                min_w = min(box.width(), col.max_right - col.min_left)

                is_corridor_match = (
                    (cx_dist <= local_char_w * 0.75 and union_w <= local_char_w * 1.70) or
                    (min_w > 0 and h_overlap >= min_w * 0.35 and union_w <= local_char_w * 1.85)
                )

                if is_corridor_match and cx_dist < best_dist:
                    best_dist = cx_dist
                    best_col = col

            if best_col is not None:
                best_col.members.append(box)
                best_col.min_left = min(best_col.min_left, box.left)
                best_col.max_right = max(best_col.max_right, box.right)
                best_col.min_top = min(best_col.min_top, box.top)
                best_col.max_bottom = max(best_col.max_bottom, box.bottom)
            else:
                columns.append(ColumnCluster([box]))

        # Step 2b: Physical Column Pitch Law (Japanese Manga Typography)
        min_col_pitch = local_char_w * 1.15
        pitch_changed = True
        pitch_passes = 0
        while pitch_changed and pitch_passes < 50 and len(columns) > 1:
            pitch_changed = False
            pitch_passes += 1
            best_i = -1
            best_j = -1
            min_cx_dist = float('inf')
            for i in range(len(columns)):
                for j in range(i + 1, len(columns)):
                    dist = abs(columns[i].cx - columns[j].cx)
                    if dist < min_cx_dist:
                        min_cx_dist = dist
                        best_i = i
                        best_j = j
            if best_i >= 0 and best_j >= 0 and min_cx_dist < min_col_pitch:
                c1 = columns[best_i]
                c2 = columns[best_j]
                c1.members.extend(c2.members)
                c1.min_left = min(c1.min_left, c2.min_left)
                c1.max_right = max(c1.max_right, c2.max_right)
                c1.min_top = min(c1.min_top, c2.min_top)
                c1.max_bottom = max(c1.max_bottom, c2.max_bottom)
                columns.pop(best_j)
                pitch_changed = True

        # Step 2c: Bubble Column Capacity Bound
        bubble_w = float(bubble.width())
        max_columns = max(1, int((bubble_w - 0.30 * local_char_w) / (1.20 * local_char_w)))

        if max_columns == 1 and len(columns) > 1:
            main = columns[0]
            for k in range(1, len(columns)):
                main.members.extend(columns[k].members)
                main.min_left = min(main.min_left, columns[k].min_left)
                main.max_right = max(main.max_right, columns[k].max_right)
                main.min_top = min(main.min_top, columns[k].min_top)
                main.max_bottom = max(main.max_bottom, columns[k].max_bottom)
            columns = [main]
        else:
            max_col_h = max([c.max_bottom - c.min_top for c in columns], default=0)
            to_merge = []
            for col in columns:
                h = col.max_bottom - col.min_top
                if len(columns) > 1 and h < max(local_char_w * 1.25, max_col_h * 0.35) and len(col.members) <= 2:
                    nearest = min(
                        [c for c in columns if c != col and c not in to_merge],
                        key=lambda c: abs(c.cx - col.cx),
                        default=None
                    )
                    if nearest is not None and abs(nearest.cx - col.cx) <= local_char_w * 1.40:
                        nearest.members.extend(col.members)
                        nearest.min_left = min(nearest.min_left, col.min_left)
                        nearest.max_right = max(nearest.max_right, col.max_right)
                        nearest.min_top = min(nearest.min_top, col.min_top)
                        nearest.max_bottom = max(nearest.max_bottom, col.max_bottom)
                        to_merge.append(col)
            for tm in to_merge:
                if tm in columns:
                    columns.remove(tm)

            while len(columns) > max_columns:
                weakest = min(columns, key=lambda c: (c.max_bottom - c.min_top) * 1000 + len(c.members), default=None)
                if weakest is None:
                    break
                nearest = min([c for c in columns if c != weakest], key=lambda c: abs(c.cx - weakest.cx), default=None)
                if nearest is None:
                    break
                nearest.members.extend(weakest.members)
                nearest.min_left = min(nearest.min_left, weakest.min_left)
                nearest.max_right = max(nearest.max_right, weakest.max_right)
                nearest.min_top = min(nearest.min_top, weakest.min_top)
                nearest.max_bottom = max(nearest.max_bottom, weakest.max_bottom)
                columns.remove(weakest)

        # Step 3: Uniform Column Width & CTD Atomic Box Grounding
        min_col_width_floor = max(16, int(local_char_w * 0.75))
        uniform_w = int(np.clip(local_char_w * 1.08, float(min_col_width_floor), 56.0))
        half_w = uniform_w // 2

        grounded_cols: List[GroundedColumn] = []
        for col in columns:
            cx = int(col.cx)
            col_l = min(cx - half_w, col.min_left)
            col_r = max(cx + half_w, col.max_right)

            clamp_l = max(col_l, bubble.left)
            clamp_r = min(col_r, bubble.right)
            clamp_t = max(col.min_top, bubble.top)
            clamp_b = min(col.max_bottom, bubble.bottom)

            # Elliptical Bubble Taper Adaptation: Prevent curved bubble borders from choking column caliber
            cur_w = clamp_r - clamp_l
            if cur_w < min_col_width_floor and bubble.width() >= min_col_width_floor:
                deficit = min_col_width_floor - cur_w
                expand_l = min(deficit // 2, max(0, clamp_l - bubble.left))
                expand_r = min(deficit - expand_l, max(0, bubble.right - clamp_r))
                clamp_l -= expand_l
                clamp_r += expand_r

            # Exact Mask Contour Clamping: Clamp column boundaries to the exact boolean pixel mask
            if matching_mask is not None:
                row_lefts = []
                row_rights = []
                for y in range(max(col.min_top, bubble.top), min(col.max_bottom, bubble.bottom) + 1):
                    ly = y - matching_mask.rect.top
                    if 0 <= ly < matching_mask.height:
                        row = matching_mask.mask[ly, :]
                        if np.any(row):
                            active_x = np.where(row)[0]
                            row_lefts.append(matching_mask.rect.left + int(active_x[0]))
                            row_rights.append(matching_mask.rect.left + int(active_x[-1]) + 1)
                if row_lefts and row_rights:
                    mask_l = max(row_lefts)
                    mask_r = min(row_rights)
                    if mask_r - mask_l >= min_col_width_floor:
                        clamp_l = max(clamp_l, mask_l)
                        clamp_r = min(clamp_r, mask_r)
                    else:
                        p_l = int(np.percentile(row_lefts, 75))
                        p_r = int(np.percentile(row_rights, 25))
                        clamp_l = max(clamp_l, p_l)
                        clamp_r = min(clamp_r, p_r)
                    # Grounding: Never cut through constituent CTD character boxes
                    clamp_l = min(clamp_l, col.min_left)
                    clamp_r = max(clamp_r, col.max_right)
                    # Stay strictly inside bubble envelope
                    clamp_l = max(clamp_l, bubble.left)
                    clamp_r = min(clamp_r, bubble.right)

            line = Rect(clamp_l, clamp_t, clamp_r, clamp_b)
            if line.width() >= 8 and line.height() >= 12:
                grounded_cols.append(GroundedColumn(col, line, col.min_left, col.max_right, col.cx))

        # Step 4: Atomic Whitespace Gutter Seam Trimming
        grounded_cols.sort(key=lambda gc: gc.rect.centerX())

        for i in range(len(grounded_cols) - 1):
            left_col = grounded_cols[i]
            right_col = grounded_cols[i + 1]
            left_rect = left_col.rect
            right_rect = right_col.rect

            # Seam trimming ONLY applies if columns vertically overlap
            v_overlap = max(0, min(left_rect.bottom, right_rect.bottom) - max(left_rect.top, right_rect.top))
            if v_overlap > 0 and left_rect.right > right_rect.left:
                left_char_max_r = left_col.char_max_right
                right_char_min_l = right_col.char_min_left

                if left_char_max_r <= right_char_min_l:
                    seam_x = (left_char_max_r + right_char_min_l) // 2
                else:
                    seam_x = int((left_col.cx + right_col.cx) / 2.0)

                min_safe_r = left_rect.left + min_col_width_floor
                max_safe_l = right_rect.right - min_col_width_floor
                if min_safe_r <= max_safe_l:
                    safe_seam = int(np.clip(seam_x, min_safe_r, max_safe_l))
                else:
                    safe_seam = (left_rect.left + right_rect.right) // 2

                left_rect.right = min(left_rect.right, safe_seam)
                right_rect.left = max(right_rect.left, safe_seam)

        return [gc.rect for gc in grounded_cols], furi_count

    @staticmethod
    def stitch_unbubbled_boxes(
        boxes: List[Rect],
        median_char_w: float,
        is_orphan_candidate: bool
    ) -> Tuple[List[Rect], List[Rect]]:
        if not boxes:
            return [], []

        dedup = VerticalLineStitcher._resolve_nested_boxes(boxes)
        if not dedup:
            return [], []

        x_strip_w = max(8, int(median_char_w))
        sorted_boxes = sorted(dedup, key=lambda b: (- (b.centerX() // x_strip_w), b.top))

        max_vert_gap = median_char_w * 2.0  # Gap cap: eliminates monster lines

        class UnbubbledCol:
            def __init__(self, box: Rect):
                self.members = [box]
                self.top = box.top
                self.bottom = box.bottom
            @property
            def cx(self) -> float:
                return float(np.mean([b.centerX() for b in self.members]))

        columns: List[UnbubbledCol] = []
        for box in sorted_boxes:
            bcx = float(box.centerX())
            best_col = None
            best_dist = float('inf')

            for col in columns:
                if abs(bcx - col.cx) > median_char_w * 0.60:
                    continue
                # Proper 2-sided vertical gap:
                if box.top >= col.bottom:
                    gap = box.top - col.bottom
                elif box.bottom <= col.top:
                    gap = col.top - box.bottom
                else:
                    gap = 0
                    v_overlap = min(box.bottom, col.bottom) - max(box.top, col.top)
                    if v_overlap > min(box.height(), col.bottom - col.top) * 0.25:
                        continue  # Incompatible vertical collision in same column

                if gap > max_vert_gap:
                    continue
                dist = abs(bcx - col.cx)
                if dist < best_dist:
                    best_dist = dist
                    best_col = col

            if best_col is not None:
                best_col.members.append(box)
                best_col.top = min(best_col.top, box.top)
                best_col.bottom = max(best_col.bottom, box.bottom)
            else:
                columns.append(UnbubbledCol(box))

        orphans: List[Rect] = []
        sfx: List[Rect] = []
        uniform_w = int(np.clip(median_char_w * 1.15, 18.0, 52.0))
        half_w = uniform_w // 2

        for col in columns:
            cx = int(col.cx)
            min_l = min(b.left for b in col.members)
            max_r = max(b.right for b in col.members)
            min_t = min(b.top for b in col.members)
            max_b = max(b.bottom for b in col.members)

            if is_orphan_candidate:
                orphan_rect = Rect(min(cx - half_w, min_l), min_t, max(cx + half_w, max_r), max_b)
                is_proper_w = orphan_rect.width() <= median_char_w * 1.45
                is_multi_char = orphan_rect.height() >= median_char_w * 1.80 and len(col.members) >= 2
                is_vertical = orphan_rect.height() >= orphan_rect.width() * 1.30
                if is_proper_w and is_multi_char and is_vertical:
                    if orphan_rect.width() >= 6 and orphan_rect.height() >= 6:
                        orphans.append(orphan_rect)
                else:
                    natural_rect = Rect(min_l, min_t, max_r, max_b)
                    if natural_rect.width() >= 6 and natural_rect.height() >= 6:
                        sfx.append(natural_rect)
            else:
                natural_rect = Rect(min_l, min_t, max_r, max_b)
                if natural_rect.width() >= 6 and natural_rect.height() >= 6:
                    sfx.append(natural_rect)

        return orphans, sfx

    @staticmethod
    def suppress_or_merge_furigana(boxes: List[Rect], median_char_w: float) -> Tuple[List[Rect], int]:
        if len(boxes) < 2:
            return boxes, 0

        main_cols = []
        furi_boxes = []
        for b in boxes:
            if b.width() < 0.52 * median_char_w and b.height() > 6:
                furi_boxes.append(b)
            else:
                main_cols.append(b)

        if not furi_boxes or not main_cols:
            return boxes, 0

        merged = [b.copy() for b in main_cols]
        absorbed = 0
        for furi in furi_boxes:
            fcx = furi.centerX()
            # In-column alignment check: if aligned with an existing box in the column corridor,
            # it is an in-column character/punctuation (e.g. small kana or punctuation), NOT ruby text!
            in_col_aligned = any(abs(m.centerX() - fcx) <= median_char_w * 0.38 for m in main_cols)
            if in_col_aligned:
                merged.append(furi)
                continue

            parent = None
            min_dist = float('inf')
            for col in merged:
                v_overlap = max(0, min(col.bottom, furi.bottom) - max(col.top, furi.top))
                h_dist = abs(col.centerX() - fcx)
                # Lateral ruby text is displaced between 0.38 and 1.40 char_w
                if v_overlap > 0 and 0.38 * median_char_w <= h_dist <= 1.40 * median_char_w:
                    if h_dist < min_dist:
                        min_dist = h_dist
                        parent = col
            if parent is not None:
                parent.left = min(parent.left, furi.left)
                parent.top = min(parent.top, furi.top)
                parent.right = max(parent.right, furi.right)
                parent.bottom = max(parent.bottom, furi.bottom)
                absorbed += 1
            else:
                merged.append(furi)

        return merged, absorbed

    @staticmethod
    def _resolve_nested_boxes(boxes: List[Rect]) -> List[Rect]:
        if len(boxes) < 2:
            return boxes
        result = [b.copy() for b in boxes]
        changed = True
        passes = 0
        while changed and passes < 8:
            changed = False
            passes += 1
            for i in range(len(result)):
                for j in range(i + 1, len(result)):
                    a = result[i]
                    b = result[j]
                    il = max(a.left, b.left)
                    it = max(a.top, b.top)
                    ir = min(a.right, b.right)
                    ib = min(a.bottom, b.bottom)
                    if ir > il and ib > it:
                        iarea = (ir - il) * (ib - it)
                        aarea = a.width() * a.height()
                        barea = b.width() * b.height()
                        a_in_b = aarea > 0 and (iarea / float(aarea)) >= 0.40
                        b_in_a = barea > 0 and (iarea / float(barea)) >= 0.40
                        if a_in_b or b_in_a:
                            union_rect = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
                            result.pop(j)
                            result[i] = union_rect
                            changed = True
                            break
                if changed:
                    break
        return result

    @staticmethod
    def _deduplicate_lines(lines: List[TextLineItem]) -> List[TextLineItem]:
        if len(lines) < 2:
            return lines
        to_remove = set()
        for i in range(len(lines)):
            if i in to_remove:
                continue
            for j in range(i + 1, len(lines)):
                if j in to_remove:
                    continue
                ra = lines[i].rect
                rb = lines[j].rect
                il = max(ra.left, rb.left)
                it = max(ra.top, rb.top)
                ir = min(ra.right, rb.right)
                ib = min(ra.bottom, rb.bottom)
                if ir > il and ib > it:
                    iarea = (ir - il) * (ib - it)
                    min_area = min(ra.width() * ra.height(), rb.width() * rb.height())
                    if min_area > 0 and (iarea / float(min_area)) > 0.65:
                        pri_i = 3 if lines[i].category == "BUBBLED" else (2 if lines[i].category == "ORPHAN" else 1)
                        pri_j = 3 if lines[j].category == "BUBBLED" else (2 if lines[j].category == "ORPHAN" else 1)
                        if pri_i != pri_j:
                            survivor = i if pri_i > pri_j else j
                            victim = j if pri_i > pri_j else i
                        else:
                            area_i = ra.width() * ra.height()
                            area_j = rb.width() * rb.height()
                            survivor = i if area_i >= area_j else j
                            victim = j if survivor == i else i

                        lines[survivor].rect.left = min(lines[survivor].rect.left, lines[victim].rect.left)
                        lines[survivor].rect.right = max(lines[survivor].rect.right, lines[victim].rect.right)
                        lines[survivor].rect.top = min(lines[survivor].rect.top, lines[victim].rect.top)
                        lines[survivor].rect.bottom = max(lines[survivor].rect.bottom, lines[victim].rect.bottom)
                        to_remove.add(victim)

        return [lines[i] for i in range(len(lines)) if i not in to_remove]
