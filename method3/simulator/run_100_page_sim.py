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

# Add engine directory to path
SIM_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SIM_DIR))

from engine.comic_text_detector import ComicTextDetector
from engine.bubble_segmenter import BubbleSegmenter
from engine.text_categorizer import TextCategorizer
from engine.vertical_line_stitcher import VerticalLineStitcher
from engine.waist_cruncher import Method3WaistEngine
from engine.defect_auditor import DefectAuditor
from engine.types import Rect

# Model Paths
CTD_MODEL = r"D:\C\KisaraTranslator\.aaa\TrPCSim\models\ai\comic_text_detector.onnx"
if not os.path.exists(CTD_MODEL):
    CTD_MODEL = r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\models\comic_text_detector.onnx"

SEG_MODEL = r"D:\C\KisaraTranslator\method3\models\manga109_segmentation_bubble_1024.onnx"
WAIST_MODEL = r"D:\C\KisaraTranslator\method3\trained_model\best.onnx"

# Database Batches
BATCHES = {
    "batch1": r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages",
    "batch2": r"D:\C\KisaraTranslator\method3\raw_manga_batch_02\pages_with_bubbles",
    "batch3": r"D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles",
    "batch4": r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles"
}

OUTPUT_DIR = SIM_DIR / "output"
LEDGER_PATH = OUTPUT_DIR / "ledger.json"

def get_sampled_pages(seed: int = 42, per_batch: int = 25):
    valid_extensions = ('.png', '.jpg', '.jpeg', '.webp')
    rng = random.Random(seed)
    sampled = []

    for batch_name, batch_path in BATCHES.items():
        if not os.path.exists(batch_path):
            raise FileNotFoundError(f"Batch path does not exist: {batch_path}")
        files = sorted([f for f in os.listdir(batch_path) if f.lower().endswith(valid_extensions)])
        if len(files) < per_batch:
            raise ValueError(f"Batch {batch_name} has only {len(files)} files, needed {per_batch}")
        selected = rng.sample(files, per_batch)
        for fname in selected:
            sampled.append({
                "batch": batch_name,
                "filename": fname,
                "filepath": os.path.join(batch_path, fname)
            })
    return sampled

def run_simulation(max_pages: Optional[int] = None):
    print("=" * 80)
    print("1:1 CRUNCH LAB PC SIMULATOR — 100-PAGE DIAGNOSTIC BATCH RUN")
    print("Thermal & Efficiency Constraints: 2 intra-op threads, sequential execution")
    print("=" * 80)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading ONNX inference engines...")
    t0_load = time.time()
    ctd_engine = ComicTextDetector(CTD_MODEL)
    seg_engine = BubbleSegmenter(SEG_MODEL)
    waist_engine = Method3WaistEngine(WAIST_MODEL)
    print(f"All engines loaded successfully in {time.time() - t0_load:.2f}s")

    pages = get_sampled_pages(seed=42, per_batch=25)
    if max_pages is not None:
        pages = pages[:max_pages]

    print(f"Total sampled pages to evaluate: {len(pages)} (25 per batch, seed=42)")
    print("-" * 80)

    ledger_records = []
    total_start = time.time()

    for idx, page_info in enumerate(pages, start=1):
        batch = page_info["batch"]
        filename = page_info["filename"]
        filepath = page_info["filepath"]
        page_id = f"{batch}_{filename}"

        p_t0 = time.time()

        # Load page image
        img_bgr = cv2.imread(filepath)
        if img_bgr is None:
            print(f"[{idx:03d}/{len(pages):03d}] ERROR: Could not read image: {filepath}")
            continue

        h_img, w_img = img_bgr.shape[:2]

        # 1. Module 1: CTD text boxes + heatmap + Step 1.3 probes
        m1_boxes, ctd_bubbles, prob_map = ctd_engine.run_module1(img_bgr)

        # 2. Module 1.2: Manga109 YOLO11-seg bubble masks
        bubble_masks = seg_engine.detect_masks(img_bgr)

        # 3. Module 1.5: Categorization (BUBBLED, ORPHAN, SFX)
        all_categorized, bubbled_b, orphan_b, sfx_b, distinct_bubbles = TextCategorizer.categorize_boxes(
            raw_boxes=m1_boxes,
            bubble_regions=ctd_bubbles,
            bubble_masks=bubble_masks,
            image_bgr=img_bgr,
            bitmap_width=w_img,
            bitmap_height=h_img,
            is_rtl=True
        )

        # 4. Module 2: Vertical Line Stitching
        all_lines, b_lines, o_lines, s_lines, furi_suppressed = VerticalLineStitcher.stitch_lines(
            categorized_boxes=all_categorized,
            distinct_bubbles=distinct_bubbles,
            bubble_masks=bubble_masks,
            image_bgr=img_bgr,
            bitmap_width=w_img,
            bitmap_height=h_img,
            is_rtl=True
        )

        # 5. Module 3: Waist Crunch & Line Partitioning
        m3_partitions = waist_engine.process_page(
            image_bgr=img_bgr,
            bubbles=distinct_bubbles,
            text_lines=all_lines
        )

        # 6. Automated Defect Auditor
        audit_res = DefectAuditor.audit_page(
            page_id=page_id,
            batch_name=batch,
            image_shape=(h_img, w_img),
            m1_boxes=m1_boxes,
            distinct_bubbles=distinct_bubbles,
            m1_5_items=all_categorized,
            m2_lines=all_lines,
            m3_partitions=m3_partitions,
            image_bgr=img_bgr
        )

        p_elapsed = (time.time() - p_t0) * 1000.0
        audit_res["elapsed_ms"] = round(p_elapsed, 1)
        audit_res["furigana_suppressed_m2"] = furi_suppressed
        audit_res["filename"] = filename
        ledger_records.append(audit_res)

        # Print clean status line
        status_msg = (
            f"[{idx:03d}/{len(pages):03d}] {batch}/{filename[:22]:<22} | "
            f"Bubs:{len(distinct_bubbles):2d} Lines:{len(all_lines):2d} "
            f"Conj:{audit_res['m3_conjoined_bubbles']:1d} | "
            f"PitchViol:{audit_res['column_pitch_violations']} "
            f"Frag:{audit_res['column_fragmentations']} "
            f"Choke:{audit_res['width_choking_count']} "
            f"Bleed:{audit_res['boundary_bleed_count']} "
            f"({p_elapsed:.0f}ms)"
        )
        print(status_msg)

        # Clean memory per page to avoid heap growth and thermal heat spikes
        del img_bgr, prob_map, bubble_masks, all_categorized, all_lines, m3_partitions
        if idx % 10 == 0:
            gc.collect()

    total_time = time.time() - total_start
    print("-" * 80)
    print(f"Simulation completed across {len(ledger_records)} pages in {total_time:.1f}s (avg {total_time/len(ledger_records)*1000:.0f}ms/page)")

    # Save structured ledger
    with open(LEDGER_PATH, "w", encoding="utf-8") as f:
        json.dump({
            "total_pages": len(ledger_records),
            "total_time_seconds": round(total_time, 2),
            "records": ledger_records
        }, f, indent=2)

    print(f"Master Error Ledger successfully written to: {LEDGER_PATH}")

if __name__ == "__main__":
    from typing import Optional
    max_p = int(sys.argv[1]) if len(sys.argv) > 1 else None
    run_simulation(max_pages=max_p)
