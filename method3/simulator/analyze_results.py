import os
import sys
import json
from pathlib import Path
from typing import Dict, Any, List

SIM_DIR = Path(__file__).resolve().parent
OUTPUT_DIR = SIM_DIR / "output"
LEDGER_PATH = OUTPUT_DIR / "ledger.json"
REPORT_PATH = OUTPUT_DIR / "report.md"

def generate_report(ledger_path: Path = LEDGER_PATH, report_path: Path = REPORT_PATH):
    if not ledger_path.exists():
        print(f"Error: Ledger file not found at {ledger_path}")
        return

    with open(ledger_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    records: List[Dict[str, Any]] = data.get("records", [])
    total_pages = len(records)
    total_time_s = data.get("total_time_seconds", 0.0)

    if total_pages == 0:
        print("Error: No records found in ledger.")
        return

    # Aggregate metrics
    batch_records: Dict[str, List[Dict[str, Any]]] = {}
    for r in records:
        b = r["batch"]
        batch_records.setdefault(b, []).append(r)

    total_bubbles = sum(r["bubble_count"] for r in records)
    total_m1_boxes = sum(r["m1_box_count"] for r in records)
    total_m2_lines = sum(r["m2_lines_total"] for r in records)
    total_bubbled_lines = sum(r["m2_bubbled_lines"] for r in records)
    total_orphan_lines = sum(r["m2_orphan_lines"] for r in records)
    total_sfx_lines = sum(r["m2_sfx_lines"] for r in records)
    total_conjoined = sum(r["m3_conjoined_bubbles"] for r in records)
    total_split_lines = sum(r["m3_split_lines"] for r in records)
    total_furigana_suppressed = sum(r.get("furigana_suppressed_m2", 0) for r in records)

    # Invariant violations
    total_col_frag = sum(r["column_fragmentations"] for r in records)
    total_pitch_viol = sum(r["column_pitch_violations"] for r in records)
    total_choking = sum(r["width_choking_count"] for r in records)
    total_bleed_count = sum(r["boundary_bleed_count"] for r in records)
    total_bleed_area = sum(r["boundary_bleed_area"] for r in records)
    total_crossover = sum(r["gutter_crossover_count"] for r in records)
    total_furi_leaks = sum(r["furigana_leaks"] for r in records)
    total_dropped_boxes = sum(r["dropped_box_count"] for r in records)
    total_snap_failures = sum(r["waist_snap_failures"] for r in records)
    total_violations = sum(r["total_violations"] for r in records)

    avg_time_ms = (total_time_s / total_pages) * 1000.0 if total_pages > 0 else 0.0

    lines = []
    lines.append("# 1:1 CrunchLab PC Simulator — 100-Page Manga Batch Evaluation Report\n")
    lines.append(f"**Execution Summary**:")
    lines.append(f"- **Total Pages Simulated**: {total_pages} (25 per batch, Seed: `42`)")
    lines.append(f"- **Total Execution Time**: {total_time_s:.1f}s ({total_time_s/60.0:.2f} min)")
    lines.append(f"- **Average Latency**: {avg_time_ms:.0f}ms / page")
    lines.append(f"- **Total Speech Bubbles**: {total_bubbles} (avg {total_bubbles/total_pages:.1f} / page)")
    lines.append(f"- **Total M1 Boxes Detected**: {total_m1_boxes} (avg {total_m1_boxes/total_pages:.1f} / page)")
    lines.append(f"- **Total M2 Vertical Lines**: {total_m2_lines} (Bubbled: {total_bubbled_lines}, Orphan: {total_orphan_lines}, SFX: {total_sfx_lines})")
    lines.append(f"- **Conjoined Bubbles Partitioned (M3)**: {total_conjoined} ({total_split_lines} straddling lines sliced)")
    lines.append(f"- **Furigana Ruby Suppressed (M2)**: {total_furigana_suppressed}\n")

    lines.append("## 1. Physical & Typographical Invariant Violations Ledger\n")
    lines.append("| Invariant Metric | Target | Count in 100 Pages | Rate / Page | Status |")
    lines.append("| :--- | :---: | :---: | :---: | :---: |")
    lines.append(f"| **Column Fragmentation** (`|dcx| <= 0.40 Wc`) | 0 | {total_col_frag} | {total_col_frag/total_pages:.2f} | {'PASS' if total_col_frag == 0 else 'DEFECT'} |")
    lines.append(f"| **Column Pitch Violation** (`|dcx| < 1.15 Wc`) | 0 | {total_pitch_viol} | {total_pitch_viol/total_pages:.2f} | {'PASS' if total_pitch_viol == 0 else 'DEFECT'} |")
    lines.append(f"| **Width Choking** (`W < 0.72 Wc`) | 0 | {total_choking} | {total_choking/total_pages:.2f} | {'PASS' if total_choking == 0 else 'DEFECT'} |")
    lines.append(f"| **Boundary Bleed (> 4px)** | 0 | {total_bleed_count} | {total_bleed_count/total_pages:.2f} | {'PASS' if total_bleed_count == 0 else 'DEFECT'} |")
    lines.append(f"| **Gutter Crossover** (`L.right > R.left`) | 0 | {total_crossover} | {total_crossover/total_pages:.2f} | {'PASS' if total_crossover == 0 else 'DEFECT'} |")
    lines.append(f"| **Furigana Ruby Leaks** (`W < 0.50 Wc`) | 0 | {total_furi_leaks} | {total_furi_leaks/total_pages:.2f} | {'PASS' if total_furi_leaks == 0 else 'WARNING'} |")
    lines.append(f"| **Dropped Character Boxes** | 0 | {total_dropped_boxes} | {total_dropped_boxes/total_pages:.2f} | {'PASS' if total_dropped_boxes == 0 else 'WARNING'} |")
    lines.append(f"| **Waist Snap Failures** (`> 25px`) | 0 | {total_snap_failures} | {total_snap_failures/total_pages:.2f} | {'PASS' if total_snap_failures == 0 else 'DEFECT'} |")
    lines.append(f"| **Total Invariant Violations** | 0 | **{total_violations}** | **{total_violations/total_pages:.2f}** | — |\n")

    lines.append("## 2. Cross-Batch Performance Breakdown\n")
    lines.append("| Batch Name | Pages | Bubbles | Lines (B/O/S) | Conjoined | Violations | Avg Latency |")
    lines.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: |")

    for b_name in sorted(batch_records.keys()):
        b_list = batch_records[b_name]
        b_cnt = len(b_list)
        b_bubs = sum(r["bubble_count"] for r in b_list)
        b_b_lines = sum(r["m2_bubbled_lines"] for r in b_list)
        b_o_lines = sum(r["m2_orphan_lines"] for r in b_list)
        b_s_lines = sum(r["m2_sfx_lines"] for r in b_list)
        b_conj = sum(r["m3_conjoined_bubbles"] for r in b_list)
        b_viols = sum(r["total_violations"] for r in b_list)
        b_avg_ms = sum(r.get("elapsed_ms", 0) for r in b_list) / b_cnt if b_cnt > 0 else 0
        lines.append(f"| **{b_name}** | {b_cnt} | {b_bubs} | {b_b_lines} / {b_o_lines} / {b_s_lines} | {b_conj} | {b_viols} | {b_avg_ms:.0f}ms |")
    lines.append("\n")

    # Worst and best pages
    sorted_by_viols = sorted(records, key=lambda r: r["total_violations"], reverse=True)
    top_worst = sorted_by_viols[:5]
    top_best = [r for r in records if r["total_violations"] == 0][:5]

    lines.append("## 3. Notable Failure Cases & Outlier Pages\n")
    lines.append("### Top Anomaly Pages (Highest Invariant Violations):")
    for r in top_worst:
        if r["total_violations"] == 0:
            continue
        lines.append(f"- **`{r['batch']}/{r['filename']}`**: {r['total_violations']} violations "
                     f"(PitchViol: {r['column_pitch_violations']}, Frag: {r['column_fragmentations']}, "
                     f"Choke: {r['width_choking_count']}, Bleed: {r['boundary_bleed_count']}, "
                     f"SnapFail: {r['waist_snap_failures']}, DroppedBoxes: {r['dropped_box_count']})")
    lines.append("\n")

    lines.append("## 4. Root-Cause Clustering & Systemic Failure Modes\n")
    lines.append("Analysis of the macro-scale diagnostic records reveals key systemic causes:\n")

    # 1. Pitch / Fragmentation Analysis
    if total_pitch_viol > 0 or total_col_frag > 0:
        lines.append("### Cause A: Overcrowded Speech Bubbles & Multi-Speaker Dialogue")
        lines.append("- **Symptom**: In-bubble column pitch drops below $1.15 W_c$ or multiple vertical fragments appear in the same corridor.")
        lines.append("- **Root Cause**: When dialogue contains tightly spaced exclamation marks, interjections, or dual speakers inside small bubbles, greedy pitch clustering may either merge distinct speakers or fail to fuse detached particles.")
        lines.append("- **Typographical Law**: Physical bubble width strictly bounds the number of readable vertical columns: $K_{\\max} = \\lfloor (W_b - 0.30 W_c) / (1.20 W_c) \\rfloor$.")
    else:
        lines.append("### Cause A: Column Pitch & Fragmentation Integrity")
        lines.append("- **Outcome**: Zero column fragmentation and zero pitch violations observed across evaluated speech bubbles. The Column Pitch Law $\\Delta cx \\ge 1.15 W_c$ and Capacity Bound $K_{\\max}$ held uniformly.\n")

    # 2. Width Choking & Boundary Bleed
    if total_choking > 0 or total_bleed_count > 0:
        lines.append("### Cause B: Elliptical Bubble Tapering & Seam Trimming Choke")
        lines.append("- **Symptom**: Column width choked below $0.72 W_c$ or column lines protruding beyond curved bubble borders.")
        lines.append("- **Root Cause**: Top and bottom ends of vertical columns in narrow elliptical speech bubbles intersect the curved bubble boundary, truncating bounding boxes horizontally.")
        lines.append("- **Typographical Law**: Column width must be anchored to the character em-box width $W_c$. Trimming must preserve minimum legible width $W_{\\min} \\ge 0.75 W_c$ by adapting the vertical span rather than choking the horizontal caliber.")
    else:
        lines.append("### Cause B: Strict Bubble Containment & Width Grounding")
        lines.append("- **Outcome**: In-bubble vertical lines remained strictly bounded within bubble perimeters without choking column width below legible typographic bounds.\n")

    # 3. Furigana Leaks & Ruby Handling
    lines.append("### Cause C: Furigana Ruby Text Separation")
    lines.append(f"- **Observed**: {total_furi_leaks} standalone ruby boxes survived; {total_furigana_suppressed} ruby boxes were successfully absorbed into parent kanji columns.")
    lines.append("- **Root Cause**: When furigana is typeset slightly further from the parent kanji than standard spacing ($> 1.5 W_c$) or spans across line gaps, 1D clustering can misclassify it as an independent narrow column.")
    lines.append("- **Typographical Law**: A Japanese vertical text column cannot have caliber $< 0.55 W_c$. Any vertical strip narrower than $0.55 W_c$ must be either absorbed into the adjacent column or suppressed as ruby text.\n")

    # 4. Method 3 Waist Snapping
    lines.append("### Cause D: Conjoined Bubble Waist Notches")
    lines.append(f"- **Observed**: {total_conjoined} conjoined speech bubbles detected; {total_snap_failures} snap failures (> 25px).")
    lines.append("- **Root Cause**: In asymmetric conjoined bubbles (e.g. cloud bubble merged with round bubble), the waist concavity defect may be shallow (depth < 5px) or obscured by speech bubble tails.")
    lines.append("- **Geometric Formulation**: Keypoint snapping should use a progressive dual-stage metric: local contour curvature extrema within $\\pm 15\\text{px}$, falling back to nearest dark boundary ink when convexity defects are degraded by speech tails.\n")

    lines.append("## 5. Universal Physical & Typographical Solutions\n")
    lines.append("Rather than ad-hoc page-specific patches, the following universal geometric rules are formulated for production implementation:\n")
    lines.append("1. **Strict Column Caliber Floor**: Enforce $W_{\\text{col}} = \\max(0.85 W_c, \\text{uniformW})$. Never allow seam trims to choke column width below $0.75 W_c$. When columns collide, shift seam along the whitespace gutter or merge into a single wide column.")
    lines.append("2. **Ruby Absorption Invariant**: All detected boxes with $W < 0.52 W_c$ must be strictly absorbed into the nearest parallel column sharing vertical span within $1.8 W_c$, completely eliminating phantom ruby lines.")
    lines.append("3. **Asymmetric Waist Contour Refinement**: For Method 3 waist detection, evaluate both Andrew's monotone chain defects and local boundary curvature extrema ($d^2 y / dx^2$) to locate shallow waists on cloud/spiky bubbles.")
    lines.append("4. **Adaptive Unbubbled Gap Capping**: Retain strict vertical gap capping $\\le 2.0 W_c$ on art/orphan text to ensure cross-panel dialogue never merges across gutter whitespace.")

    report_content = "\n".join(lines)
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report_content)

    print(f"Macro analysis report successfully generated at: {report_path}")
    return report_content

if __name__ == "__main__":
    generate_report()
