import os
import sys
import cv2
import numpy as np
from pathlib import Path

SIM_DIR = Path('D:/C/KisaraTranslator/method3/simulator')
sys.path.insert(0, str(SIM_DIR))

from engine.comic_text_detector import ComicTextDetector
from engine.bubble_segmenter import BubbleSegmenter
from engine.text_categorizer import TextCategorizer
from engine.vertical_line_stitcher import VerticalLineStitcher
from engine.inpaint_approaches import (
    inpaint_app0_baseline_laplacian,
    inpaint_app1_statistical_median,
    inpaint_app2_clean_gated_telea,
    inpaint_app3_adaptive_hybrid
)

CTD_MODEL = r"D:\C\KisaraTranslator\.aaa\TrPCSim\models\ai\comic_text_detector.onnx"
SEG_MODEL = r"D:\C\KisaraTranslator\method3\models\manga109_segmentation_bubble_1024.onnx"

ctd = ComicTextDetector(CTD_MODEL)
seg = BubbleSegmenter(SEG_MODEL)

ARTIFACT_DIR = Path(r"C:\Users\akhla\.gemini\antigravity\brain\779c9e56-e0ab-42e3-a952-9e1be7ff2e16")

PAGES = [
    {
        "name": "doctors_knife_004",
        "path": r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\doctors_knife_004.jpg",
        "focus_line_idx": 1 # "ジュービーの声を聴くだけで機嫌が悪くな..."
    },
    {
        "name": "doctors_knife_005",
        "path": r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\doctors_knife_005.jpg",
        "focus_line_idx": 0 # "でも今日は絶対に 漫画を終わらせたい"
    },
    {
        "name": "c07_p096",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles\c07_p096.webp",
        "focus_line_idx": 0 # "タヌキは冬眠しませんが"
    }
]

def add_header(img: np.ndarray, text: str, color=(35, 35, 35)) -> np.ndarray:
    h, w = 36, img.shape[1]
    banner = np.full((h, w, 3), color, dtype=np.uint8)
    font = cv2.FONT_HERSHEY_SIMPLEX
    (tw, th), _ = cv2.getTextSize(text, font, 0.45, 1)
    tx = max(4, (w - tw) // 2)
    ty = (h + th) // 2
    cv2.putText(banner, text, (tx, ty), font, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
    return np.vstack([banner, img])

for pinfo in PAGES:
    pname = pinfo["name"]
    ppath = pinfo["path"]
    if not os.path.exists(ppath): continue

    img = cv2.imread(ppath)
    h, w = img.shape[:2]

    m1_b, ctd_b, prob = ctd.run_module1(img)
    masks = seg.detect_masks(img)
    all_cat, b_b, o_b, s_b, dist = TextCategorizer.categorize_boxes(m1_b, [], masks, img, w, h, ctd_bubbles=ctd_b, is_rtl=True)
    all_l, b_l, o_l, s_l, f = VerticalLineStitcher.stitch_lines(all_cat, dist, masks, img, w, h, is_rtl=True)

    if not o_l:
        print(f"Skipping {pname}, no o_lines")
        continue

    # Pick focus line or cluster
    f_idx = min(pinfo["focus_line_idx"], len(o_l) - 1)
    target_line = o_l[f_idx]

    # Find all orphan lines close to target line (same dialogue bubble/block)
    cluster = [o for o in o_l if abs(o.rect.centerX() - target_line.rect.centerX()) < 120 and abs(o.rect.centerY() - target_line.rect.centerY()) < 150]
    if not cluster: cluster = [target_line]

    pad = 20
    min_l = max(0, min(o.rect.left for o in cluster) - pad)
    min_t = max(0, min(o.rect.top for o in cluster) - pad)
    max_r = min(w, max(o.rect.right for o in cluster) + pad)
    max_b = min(h, max(o.rect.bottom for o in cluster) + pad)

    crop = img[min_t:max_b, min_l:max_r].copy()
    cw = max_r - min_l
    ch = max_b - min_t

    scale_x = 1024.0 / float(w)
    scale_y = 1024.0 / float(h)
    gx = np.clip((np.arange(min_l, max_r) * scale_x).astype(np.int32), 0, 1023)
    gy = np.clip((np.arange(min_t, max_b) * scale_y).astype(np.int32), 0, 1023)
    hm_patch = prob[np.ix_(gy, gx)]

    local_lines = [
        (max(0, o.rect.left - min_l), max(0, o.rect.top - min_t), min(cw, o.rect.right - min_l), min(ch, o.rect.bottom - min_t))
        for o in cluster
    ]

    core_mask = hm_patch >= 0.18
    k_el = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    unified_mask = cv2.dilate(core_mask.astype(np.uint8), k_el) > 0
    unified_mask[0, :] = False; unified_mask[-1, :] = False; unified_mask[:, 0] = False; unified_mask[:, -1] = False

    res_base = inpaint_app0_baseline_laplacian(crop, local_lines, hm_patch)
    res_med = inpaint_app1_statistical_median(crop, unified_mask)
    res_telea = inpaint_app2_clean_gated_telea(crop, unified_mask)
    res_hyb = inpaint_app3_adaptive_hybrid(crop, unified_mask)

    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    s_base = float(np.std(cv2.cvtColor(res_base, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0
    s_med = float(np.std(cv2.cvtColor(res_med, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0
    s_telea = float(np.std(cv2.cvtColor(res_telea, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0
    s_hyb = float(np.std(cv2.cvtColor(res_hyb, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0

    p1 = add_header(crop, "ORIGINAL", (40, 40, 40))
    p2 = add_header(res_base, f"BASELINE (std:{s_base:.1f})", (110, 30, 30))
    p3 = add_header(res_med, f"MEDIAN (std:{s_med:.1f})", (30, 95, 30))
    p4 = add_header(res_telea, f"TELEA (std:{s_telea:.1f})", (30, 60, 110))
    p5 = add_header(res_hyb, f"HYBRID (std:{s_hyb:.1f})", (85, 35, 95))

    comp = np.hstack([p1, p2, p3, p4, p5])
    out_file = ARTIFACT_DIR / f"verified_orphan_{pname}.jpg"
    cv2.imwrite(str(out_file), comp)
    print(f"Generated comparison for {pname} -> {out_file}", flush=True)

print("ALL VERIFIED COMPARISONS COMPLETE!")
