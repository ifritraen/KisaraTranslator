import numpy as np
from typing import List, Dict, Any, Tuple
from .types import Rect, TextLineItem, CrunchPartitionItem

class DefectAuditor:
    """
    Automated Physical & Typographical Invariant Auditor.
    Quantifies geometric and topological defects across the 100-page benchmark run.
    """

    @staticmethod
    def audit_page(
        page_id: str,
        batch_name: str,
        image_shape: Tuple[int, int],
        m1_boxes: List[Rect],
        distinct_bubbles: List[Rect],
        m1_5_items: List[TextLineItem],
        m2_lines: List[TextLineItem],
        m3_partitions: List[CrunchPartitionItem],
        image_bgr: Optional[np.ndarray] = None
    ) -> Dict[str, Any]:
        h_img, w_img = image_shape

        # Median character width from standard boxes
        std_boxes = [b for b in m1_boxes if b.height() >= 16 and b.width() >= 12]
        if std_boxes:
            sw = sorted([b.width() for b in std_boxes])
            global_char_w = float(np.clip(sw[len(sw) // 2], 18.0, 48.0))
        else:
            global_char_w = 26.0

        bubbled_lines = [l for l in m2_lines if l.category == "BUBBLED"]
        orphan_lines = [l for l in m2_lines if l.category == "ORPHAN"]
        sfx_lines = [l for l in m2_lines if l.category == "SFX"]

        column_fragmentations = 0
        column_pitch_violations = 0
        width_choking_count = 0
        boundary_bleed_count = 0
        boundary_bleed_area = 0
        gutter_crossover_count = 0
        furigana_leaks = 0

        # Map each bubbled line to its primary speech bubble (maximum intersection area)
        bubble_to_lines = {id(b): [] for b in distinct_bubbles}
        for l in bubbled_lines:
            best_bubble = None
            max_inter = 0
            for b in distinct_bubbles:
                il = max(b.left, l.rect.left)
                it = max(b.top, l.rect.top)
                ir = min(b.right, l.rect.right)
                ib = min(b.bottom, l.rect.bottom)
                if ir > il and ib > it:
                    inter_a = (ir - il) * (ib - it)
                    if inter_a > max_inter:
                        max_inter = inter_a
                        best_bubble = b
            if best_bubble is not None and max_inter > 0:
                bubble_to_lines[id(best_bubble)].append(l)

        # 1. In-bubble audits per speech bubble
        for bubble in distinct_bubbles:
            lines_in_b = bubble_to_lines.get(id(bubble), [])
            if not lines_in_b:
                continue

            # Local char width matching stitch_bubble_columns scaling
            in_std = [b for b in m1_boxes if bubble.contains(b.centerX(), b.centerY()) and b.height() >= 16 and b.width() >= 12]
            max_allowed_w = max(48.0, global_char_w * 1.5)
            if in_std:
                local_char_w = float(np.clip(np.median([b.width() for b in in_std]), 16.0, max_allowed_w))
            else:
                local_char_w = float(np.clip(global_char_w, 16.0, max_allowed_w))

            # Column fragmentation check: lines sharing the same X-corridor (|dcx| <= 0.40 * localCharW)
            for i in range(len(lines_in_b)):
                for j in range(i + 1, len(lines_in_b)):
                    dcx = abs(lines_in_b[i].rect.centerX() - lines_in_b[j].rect.centerX())
                    if dcx <= 0.40 * local_char_w:
                        column_fragmentations += 1

            # Column pitch check: adjacent lines with pitch < 1.15 * localCharW that share vertical span
            sorted_by_x = sorted(lines_in_b, key=lambda l: l.rect.centerX())
            for i in range(len(sorted_by_x) - 1):
                l1 = sorted_by_x[i]
                l2 = sorted_by_x[i + 1]
                v_overlap = max(0, min(l1.rect.bottom, l2.rect.bottom) - max(l1.rect.top, l2.rect.top))
                if v_overlap > 0:
                    dcx = abs(l1.rect.centerX() - l2.rect.centerX())
                    # Column pitch check: adjacent lines that collide or have fragmented pitch (< 0.85 Wc)
                    if dcx < 0.85 * local_char_w or (l1.rect.right > l2.rect.left and dcx < 1.15 * local_char_w):
                        column_pitch_violations += 1

                    # Gutter crossover: left.right > right.left within same vertical span
                    if l1.rect.right > l2.rect.left:
                        gutter_crossover_count += 1

            # Width choking & boundary bleed
            for l in lines_in_b:
                if l.rect.width() < 0.72 * local_char_w:
                    width_choking_count += 1

                # Bleed outside bubble (> 4px margin)
                excess_l = max(0, bubble.left - l.rect.left)
                excess_r = max(0, l.rect.right - bubble.right)
                excess_t = max(0, bubble.top - l.rect.top)
                excess_b = max(0, l.rect.bottom - bubble.bottom)
                if excess_l > 4 or excess_r > 4 or excess_t > 4 or excess_b > 4:
                    boundary_bleed_count += 1
                    bleed_w = (excess_l + excess_r)
                    bleed_h = (excess_t + excess_b)
                    boundary_bleed_area += bleed_w * bleed_h

        # 2. Furigana leaks across all lines
        for l in m2_lines:
            if l.rect.width() < 0.50 * global_char_w and l.rect.height() > 16:
                furigana_leaks += 1

        # 3. Dropped Box Count: genuine non-blank character boxes not covered by M2 lines
        from .vertical_line_stitcher import VerticalLineStitcher
        dropped_box_count = 0
        for item in m1_5_items:
            b = item.rect
            if image_bgr is not None and VerticalLineStitcher.is_box_blank(image_bgr, b):
                continue
            bcx = b.centerX()
            bcy = b.centerY()
            covered = any(
                l.rect.contains(bcx, bcy) or
                (max(l.rect.left, b.left) < min(l.rect.right, b.right) and
                 max(l.rect.top, b.top) < min(l.rect.bottom, b.bottom) and
                 (min(l.rect.right, b.right) - max(l.rect.left, b.left)) * (min(l.rect.bottom, b.bottom) - max(l.rect.top, b.top)) >= 0.35 * b.width() * b.height())
                for l in m2_lines
            )
            if not covered:
                dropped_box_count += 1

        # 4. Method 3 Waist Snapping
        conjoined_count = 0
        waist_snap_failures = 0
        split_line_count = 0
        for part in m3_partitions:
            if part.is_conjoined:
                conjoined_count += 1
                split_line_count += len(part.split_lines)
                if part.p1_raw and part.p1_snapped and part.p2_raw and part.p2_snapped:
                    d1 = np.sqrt((part.p1_raw[0] - part.p1_snapped[0]) ** 2 + (part.p1_raw[1] - part.p1_snapped[1]) ** 2)
                    d2 = np.sqrt((part.p2_raw[0] - part.p2_snapped[0]) ** 2 + (part.p2_raw[1] - part.p2_snapped[1]) ** 2)
                    # Adaptive snap tolerance scaling with bubble dimensions
                    b_w = float(part.bubble_rect.width())
                    b_h = float(part.bubble_rect.height())
                    tau_snap = max(45.0, 0.12 * min(b_w, b_h))
                    if d1 > tau_snap or d2 > tau_snap:
                        waist_snap_failures += 1

        total_violations = (
            column_fragmentations +
            column_pitch_violations +
            width_choking_count +
            boundary_bleed_count +
            gutter_crossover_count +
            waist_snap_failures +
            dropped_box_count
        )

        return {
            "page_id": page_id,
            "batch": batch_name,
            "image_dims": [w_img, h_img],
            "global_char_w": global_char_w,
            "m1_box_count": len(m1_boxes),
            "bubble_count": len(distinct_bubbles),
            "m1_5_bubbled": len([b for b in m1_5_boxes if b.category == "BUBBLED"]) if (m1_5_boxes := m1_5_items) else 0,
            "m1_5_orphan": len([b for b in m1_5_items if b.category == "ORPHAN"]),
            "m1_5_sfx": len([b for b in m1_5_items if b.category == "SFX"]),
            "m2_lines_total": len(m2_lines),
            "m2_bubbled_lines": len(bubbled_lines),
            "m2_orphan_lines": len(orphan_lines),
            "m2_sfx_lines": len(sfx_lines),
            "m3_conjoined_bubbles": conjoined_count,
            "m3_split_lines": split_line_count,
            "column_fragmentations": column_fragmentations,
            "column_pitch_violations": column_pitch_violations,
            "width_choking_count": width_choking_count,
            "boundary_bleed_count": boundary_bleed_count,
            "boundary_bleed_area": boundary_bleed_area,
            "gutter_crossover_count": gutter_crossover_count,
            "furigana_leaks": furigana_leaks,
            "dropped_box_count": dropped_box_count,
            "waist_snap_failures": waist_snap_failures,
            "total_violations": total_violations
        }
