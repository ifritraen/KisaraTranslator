import os
import sys
import cv2
import json
import numpy as np
from pathlib import Path

SIM_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SIM_DIR))

from engine.comic_text_detector import ComicTextDetector
from engine.bubble_segmenter import BubbleSegmenter
from engine.text_categorizer import TextCategorizer
from engine.vertical_line_stitcher import VerticalLineStitcher
from engine.waist_cruncher import Method3WaistEngine, ConvexityDefectDetector
from engine.types import Rect

CTD_MODEL = r"D:\C\KisaraTranslator\.aaa\TrPCSim\models\ai\comic_text_detector.onnx"
SEG_MODEL = r"D:\C\KisaraTranslator\method3\models\manga109_segmentation_bubble_1024.onnx"
WAIST_MODEL = r"D:\C\KisaraTranslator\method3\trained_model\best.onnx"

ctd = ComicTextDetector(CTD_MODEL)
seg = BubbleSegmenter(SEG_MODEL)
waist_engine = Method3WaistEngine(WAIST_MODEL)

test_waist_files = [
    (r"c:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\mnemosyne_005.png", "batch1/mnemosyne_005.png"),
    (r"c:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\jp_manga_18_006.jpg", "batch1/jp_manga_18_006.jpg"),
    (r"D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles\c20_p034.webp", "batch3/c20_p034.webp"),
    (r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles\c07_p033.webp", "batch4/c07_p033.webp"),
]

for img_path, label in test_waist_files:
    if not os.path.exists(img_path):
        continue
    img = cv2.imread(img_path)
    h_img, w_img = img.shape[:2]
    m1_boxes, ctd_bubbles, prob = ctd.run_module1(img)
    bubble_masks = seg.detect_masks(img)
    _, _, _, _, distinct_bubbles = TextCategorizer.categorize_boxes(
        raw_boxes=m1_boxes, bubble_regions=ctd_bubbles, bubble_masks=bubble_masks,
        image_bgr=img, bitmap_width=w_img, bitmap_height=h_img, is_rtl=True
    )
    print(f"\n=== {label} (bubbles={len(distinct_bubbles)}) ===")
    for b_idx, b_rect in enumerate(distinct_bubbles):
        pad = 4
        left = max(0, min(w_img - 1, b_rect.left - pad))
        top = max(0, min(h_img - 1, b_rect.top - pad))
        right = max(left + 1, min(w_img, b_rect.right + pad))
        bottom = max(top + 1, min(h_img, b_rect.bottom + pad))
        crop = img[top:bottom, left:right]
        pred = waist_engine.predict_crop(crop)
        if pred and pred["is_conjoined"]:
            p1_raw = pred["p1_raw"]
            p2_raw = pred["p2_raw"]
            p1_s = pred["p1_snapped"]
            p2_s = pred["p2_snapped"]
            d1 = np.sqrt((p1_raw[0] - p1_s[0])**2 + (p1_raw[1] - p1_s[1])**2)
            d2 = np.sqrt((p2_raw[0] - p2_s[0])**2 + (p2_raw[1] - p2_s[1])**2)
            cands = pred["candidates"]
            print(f"  Bubble {b_idx} {b_rect} (crop={crop.shape[1]}x{crop.shape[0]}):")
            print(f"    cands count={len(cands)}, cands={cands}")
            print(f"    P1: raw={p1_raw}, snap={p1_s}, dist={d1:.1f}")
            print(f"    P2: raw={p2_raw}, snap={p2_s}, dist={d2:.1f}")
            if d1 > 25.0 or d2 > 25.0:
                print(f"    *** SNAP FAILURE (> 25px)! ***")
