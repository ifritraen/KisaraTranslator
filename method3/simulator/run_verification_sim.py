import os
import sys
import gc
import time
import json
import random
import cv2
import numpy as np
from pathlib import Path
from typing import Optional, List, Dict, Any

SIM_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SIM_DIR))

from engine.comic_text_detector import ComicTextDetector
from engine.bubble_segmenter import BubbleSegmenter
from engine.text_categorizer import TextCategorizer
from engine.vertical_line_stitcher import VerticalLineStitcher
from engine.waist_cruncher import Method3WaistEngine
from engine.defect_auditor import DefectAuditor

CTD_MODEL = r"D:\C\KisaraTranslator\.aaa\TrPCSim\models\ai\comic_text_detector.onnx"
SEG_MODEL = r"D:\C\KisaraTranslator\method3\models\manga109_segmentation_bubble_1024.onnx"
WAIST_MODEL = r"D:\C\KisaraTranslator\method3\trained_model\best.onnx"

BATCHES = {
    "batch1": r"c:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages",
    "batch2": r"D:\C\KisaraTranslator\method3\raw_manga_batch_02\pages_with_bubbles",
    "batch3": r"D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles",
    "batch4": r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles"
}

OUTPUT_DIR = SIM_DIR / "output"
VERIF_LEDGER_PATH = OUTPUT_DIR / "verification_ledger.json"
CHECKPOINT_PATH = OUTPUT_DIR / "verification_checkpoint.json"

def get_verification_sample(seed: int = 1337, per_batch: int = 4):
    valid_exts = ('.png', '.jpg', '.jpeg', '.webp')
    rng = random.Random(seed)
    sampled = []
    for bname, bpath in BATCHES.items():
        if not os.path.exists(bpath):
            raise FileNotFoundError(f"Missing batch path: {bpath}")
        files = sorted([f for f in os.listdir(bpath) if f.lower().endswith(valid_exts)])
        chosen = rng.sample(files, per_batch)
        for fn in chosen:
            sampled.append({
                "batch": bname,
                "filename": fn,
                "filepath": os.path.join(bpath, fn)
            })
    return sampled

def run_verification(seed: int = 1337, per_batch: int = 4):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    pages = get_verification_sample(seed=seed, per_batch=per_batch)
    print("=" * 80)
    print(f"VERIFICATION SIMULATION RUN: {len(pages)} RANDOM RAW PAGES ({per_batch}/batch, seed={seed})")
    print("Thermal & Efficiency Constraints: 2 intra-op ONNX threads, sequential, purge memory")
    print("=" * 80)

    t0_load = time.time()
    ctd_engine = ComicTextDetector(CTD_MODEL)
    seg_engine = BubbleSegmenter(SEG_MODEL)
    waist_engine = Method3WaistEngine(WAIST_MODEL)
    print(f"ONNX Engines loaded in {time.time() - t0_load:.2f}s\n")

    ledger_records = []
    total_start = time.time()

    for idx, pinfo in enumerate(pages, start=1):
        batch = pinfo["batch"]
        fname = pinfo["filename"]
        fpath = pinfo["filepath"]
        page_id = f"{batch}_{fname}"

        p_t0 = time.time()
        img_bgr = cv2.imread(fpath)
        if img_bgr is None:
            print(f"[{idx:02d}/{len(pages):02d}] ERROR reading: {fpath}")
            continue

        h_img, w_img = img_bgr.shape[:2]

        # 1. Module 1
        m1_boxes, ctd_bubbles, prob_map = ctd_engine.run_module1(img_bgr)

        # 2. Module 1.2
        bubble_masks = seg_engine.detect_masks(img_bgr)

        # 2b. Conjoined Lobe Partition before column stitching
        bubble_masks, distinct_bubbles, m3_parts = waist_engine.partition_conjoined_bubbles(
            image_bgr=img_bgr, bubble_masks=bubble_masks, ctd_bubbles=ctd_bubbles
        )

        # 3. Module 1.5
        all_categorized, b_boxes, o_boxes, s_boxes, distinct_bubbles = TextCategorizer.categorize_boxes(
            raw_boxes=m1_boxes, bubble_regions=distinct_bubbles, bubble_masks=bubble_masks,
            image_bgr=img_bgr, bitmap_width=w_img, bitmap_height=h_img, is_rtl=True
        )

        # 4. Module 2
        all_lines, b_lines, o_lines, s_lines, furi = VerticalLineStitcher.stitch_lines(
            categorized_boxes=all_categorized, distinct_bubbles=distinct_bubbles, bubble_masks=bubble_masks,
            image_bgr=img_bgr, bitmap_width=w_img, bitmap_height=h_img, is_rtl=True
        )

        # 5. Module 3: Line assignment to lobes
        waist_engine.assign_partition_lines(m3_parts, all_lines)

        # 6. Audit
        audit = DefectAuditor.audit_page(
            page_id=page_id, batch_name=batch, image_shape=(h_img, w_img),
            m1_boxes=m1_boxes, distinct_bubbles=distinct_bubbles, m1_5_items=all_categorized,
            m2_lines=all_lines, m3_partitions=m3_parts, image_bgr=img_bgr, bubble_masks=bubble_masks
        )

        # Strict Invariant Assertions
        assert audit['dropped_box_count'] == 0, f"Invariant violation: {audit['dropped_box_count']} dropped boxes in {page_id}"
        assert audit['empty_bubbles_with_ink'] == 0, f"Invariant violation: {audit['empty_bubbles_with_ink']} empty bubbles in {page_id}"
        assert audit['mask_boundary_bleed_count'] == 0, f"Invariant violation: {audit['mask_boundary_bleed_count']} mask boundary bleeds in {page_id}"

        elapsed = (time.time() - p_t0) * 1000.0
        audit["elapsed_ms"] = round(elapsed, 1)
        audit["furigana_suppressed_m2"] = furi
        audit["filename"] = fname
        ledger_records.append(audit)

        # Checkpoint continuously
        with open(CHECKPOINT_PATH, "w", encoding="utf-8") as f:
            json.dump({"completed": idx, "total": len(pages), "latest_record": audit}, f, indent=2)

        status_msg = (
            f"[{idx:02d}/{len(pages):02d}] {batch}/{fname[:20]:<20} | "
            f"Bubs:{len(distinct_bubbles):2d} Lines:{len(all_lines):2d} "
            f"(B:{audit['m2_bubbled_lines']:2d}, O:{audit['m2_orphan_lines']:2d}, S:{audit['m2_sfx_lines']:2d}) | "
            f"Viols:{audit['total_violations']} "
            f"(Pitch:{audit['column_pitch_violations']} Frag:{audit['column_fragmentations']} "
            f"Choke:{audit['width_choking_count']} Bleed:{audit['boundary_bleed_count']} "
            f"Cross:{audit['gutter_crossover_count']} Snap:{audit['waist_snap_failures']} "
            f"Drop:{audit['dropped_box_count']} Empty:{audit['empty_bubbles_with_ink']}) | {elapsed:.0f}ms"
        )
        print(status_msg)

        # Clean memory immediately to ensure zero thermal heat spikes
        del img_bgr, prob_map, bubble_masks, all_categorized, all_lines, m3_parts
        gc.collect()

    total_time = time.time() - total_start
    print("-" * 80)
    print(f"Verification completed: {len(ledger_records)} pages in {total_time:.1f}s (avg {total_time/len(ledger_records)*1000:.0f}ms/page)")

    # Save ledger
    with open(VERIF_LEDGER_PATH, "w", encoding="utf-8") as f:
        json.dump({
            "total_pages": len(ledger_records),
            "total_time_seconds": round(total_time, 2),
            "records": ledger_records
        }, f, indent=2)

    print(f"Verification Ledger written to: {VERIF_LEDGER_PATH}")

    # Summary
    total_viols = sum(r["total_violations"] for r in ledger_records)
    total_pitch = sum(r["column_pitch_violations"] for r in ledger_records)
    total_frag = sum(r["column_fragmentations"] for r in ledger_records)
    total_choke = sum(r["width_choking_count"] for r in ledger_records)
    total_bleed = sum(r["boundary_bleed_count"] for r in ledger_records)
    total_cross = sum(r["gutter_crossover_count"] for r in ledger_records)
    total_snap = sum(r["waist_snap_failures"] for r in ledger_records)
    total_dropped = sum(r["dropped_box_count"] for r in ledger_records)

    print("\n" + "=" * 40)
    print("VERIFICATION RUN AUDIT SUMMARY")
    print("=" * 40)
    print(f"Total Physical Violations: {total_viols}")
    print(f"  Column Pitch Violations: {total_pitch}")
    print(f"  Column Fragmentations:   {total_frag}")
    print(f"  Width Choking:           {total_choke}")
    print(f"  Boundary Bleed:          {total_bleed}")
    print(f"  Gutter Crossover:        {total_cross}")
    print(f"  Waist Snap Failures:     {total_snap}")
    print(f"  Dropped Character Boxes: {total_dropped}")
    print("=" * 40)

if __name__ == "__main__":
    run_verification(seed=1337, per_batch=4)
