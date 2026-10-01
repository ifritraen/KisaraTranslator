import os
import sys
import cv2
from pathlib import Path

SIM_DIR = Path('D:/C/KisaraTranslator/method3/simulator')
sys.path.insert(0, str(SIM_DIR))

from engine.comic_text_detector import ComicTextDetector
from engine.bubble_segmenter import BubbleSegmenter
from engine.text_categorizer import TextCategorizer
from engine.vertical_line_stitcher import VerticalLineStitcher

ctd = ComicTextDetector(r'D:\C\KisaraTranslator\.aaa\TrPCSim\models\ai\comic_text_detector.onnx')
seg = BubbleSegmenter(r'D:\C\KisaraTranslator\method3\models\manga109_segmentation_bubble_1024.onnx')

batches = {
    'batch1': r'C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages',
    'batch2': r'D:\C\KisaraTranslator\method3\raw_manga_batch_02\pages_with_bubbles',
    'batch3': r'D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles',
    'batch4': r'D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles'
}

out_dir = Path(r'D:\C\KisaraTranslator\method3\simulator\output\verified_orphan_search')
out_dir.mkdir(parents=True, exist_ok=True)

verified_pages = []

for bname, bpath in batches.items():
    files = sorted([f for f in os.listdir(bpath) if f.lower().endswith(('.webp', '.png', '.jpg'))])
    b_count = 0
    for fname in files:
        fpath = os.path.join(bpath, fname)
        img = cv2.imread(fpath)
        if img is None: continue
        h, w = img.shape[:2]

        m1_boxes, ctd_bubbles, prob = ctd.run_module1(img)
        masks = seg.detect_masks(img)

        # Categorize WITH ctd_bubbles!
        all_cat, b_b, o_b, s_b, dist = TextCategorizer.categorize_boxes(
            raw_boxes=m1_boxes, bubble_regions=[], bubble_masks=masks,
            image_bgr=img, bitmap_width=w, bitmap_height=h, ctd_bubbles=ctd_bubbles, is_rtl=True
        )

        all_l, b_l, o_l, s_l, f = VerticalLineStitcher.stitch_lines(
            categorized_boxes=all_cat, distinct_bubbles=dist, bubble_masks=masks,
            image_bgr=img, bitmap_width=w, bitmap_height=h, is_rtl=True
        )

        # Look for genuine dialogue lines: height >= 50px, width >= 14px, and at least 3 character boxes in the line
        real_o_lines = [o for o in o_l if o.rect.height() >= 60 and o.rect.width() >= 14]
        if len(real_o_lines) >= 1:
            # Crop around the real orphan lines
            pad = 24
            min_l = max(0, min(o.rect.left for o in real_o_lines) - pad)
            min_t = max(0, min(o.rect.top for o in real_o_lines) - pad)
            max_r = min(w, max(o.rect.right for o in real_o_lines) + pad)
            max_b = min(h, max(o.rect.bottom for o in real_o_lines) + pad)

            crop = img[min_t:max_b, min_l:max_r]
            crop_name = f"{bname}_{fname}_crop.jpg"
            cv2.imwrite(str(out_dir / crop_name), crop)

            verified_pages.append({
                'batch': bname,
                'filename': fname,
                'real_o_lines': len(real_o_lines),
                'crop_file': str(out_dir / crop_name)
            })
            b_count += 1
            print(f"VERIFIED: {bname}/{fname} -> {len(real_o_lines)} genuine orphan lines saved to {crop_name}", flush=True)

            if b_count >= 3:  # 3 per batch = 12 total!
                break

print(f"\nTOTAL VERIFIED GENUINE ORPHAN PAGES FOUND: {len(verified_pages)}")
