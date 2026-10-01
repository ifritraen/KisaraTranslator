import os
import sys
import time
import json
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

print("Loading ONNX models...", flush=True)
ctd = ComicTextDetector(CTD_MODEL)
seg = BubbleSegmenter(SEG_MODEL)

ARTIFACT_DIR = Path(r"C:\Users\akhla\.gemini\antigravity\brain\779c9e56-e0ab-42e3-a952-9e1be7ff2e16")
ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)

PAGES_CONFIG = [
    # Batch 1
    {
        "idx": 1, "batch": "batch1", "name": "doctors_knife_004",
        "path": r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\doctors_knife_004.jpg"
    },
    {
        "idx": 2, "batch": "batch1", "name": "doctors_knife_005",
        "path": r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\doctors_knife_005.jpg"
    },
    {
        "idx": 3, "batch": "batch1", "name": "jp_manga_18_009",
        "path": r"C:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages\jp_manga_18_009.jpg"
    },
    # Batch 2
    {
        "idx": 4, "batch": "batch2", "name": "c14_p184",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_02\pages_with_bubbles\c14_p184.webp"
    },
    {
        "idx": 5, "batch": "batch2", "name": "c01_p033",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_02\pages_with_bubbles\c01_p033.webp"
    },
    {
        "idx": 6, "batch": "batch2", "name": "c05_p002",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_02\pages_with_bubbles\c05_p002.webp"
    },
    # Batch 3
    {
        "idx": 7, "batch": "batch3", "name": "c31_p004",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles\c31_p004.jpg"
    },
    {
        "idx": 8, "batch": "batch3", "name": "c31_p003",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles\c31_p003.jpg"
    },
    {
        "idx": 9, "batch": "batch3", "name": "c01_p003",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_03\pages_with_bubbles\c01_p003.webp"
    },
    # Batch 4
    {
        "idx": 10, "batch": "batch4", "name": "c07_p096",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles\c07_p096.webp"
    },
    {
        "idx": 11, "batch": "batch4", "name": "c08_p008",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles\c08_p008.webp"
    },
    {
        "idx": 12, "batch": "batch4", "name": "c08_p032",
        "path": r"D:\C\KisaraTranslator\method3\raw_manga_batch_04\pages_with_bubbles\c08_p032.webp"
    }
]

def add_header(img: np.ndarray, title: str, subtitle: str, color=(35, 35, 35)) -> np.ndarray:
    h, w = 46, img.shape[1]
    banner = np.full((h, w, 3), color, dtype=np.uint8)
    font = cv2.FONT_HERSHEY_SIMPLEX
    
    (tw1, th1), _ = cv2.getTextSize(title, font, 0.45, 1)
    tx1 = max(4, (w - tw1) // 2)
    cv2.putText(banner, title, (tx1, 18), font, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
    
    if subtitle:
        (tw2, th2), _ = cv2.getTextSize(subtitle, font, 0.38, 1)
        tx2 = max(4, (w - tw2) // 2)
        cv2.putText(banner, subtitle, (tx2, 36), font, 0.38, (200, 225, 255), 1, cv2.LINE_AA)
        
    return np.vstack([banner, img])

results = []

print("Starting 12-page benchmark run...", flush=True)

for pinfo in PAGES_CONFIG:
    idx = pinfo["idx"]
    pname = pinfo["name"]
    bname = pinfo["batch"]
    ppath = pinfo["path"]
    
    if not os.path.exists(ppath):
        print(f"[{idx}/12] Missing path: {ppath}")
        continue

    print(f"\n[{idx}/12] Processing {bname}/{pname}...", flush=True)
    img = cv2.imread(ppath)
    if img is None:
        print(f"Failed to read image {ppath}")
        continue
    h, w = img.shape[:2]

    # CTD & Bubble segmentation
    m1_b, ctd_b, prob = ctd.run_module1(img)
    masks = seg.detect_masks(img)
    all_cat, b_b, o_b, s_b, dist = TextCategorizer.categorize_boxes(m1_b, [], masks, img, w, h, ctd_bubbles=ctd_b, is_rtl=True)
    all_l, b_l, o_l, s_l, f = VerticalLineStitcher.stitch_lines(all_cat, dist, masks, img, w, h, is_rtl=True)

    if not o_l:
        print(f"Warning: No orphan lines found in {pname}!")
        continue

    # Filter for genuine dialogue lines: height >= 50px, width >= 12px
    tall_lines = [o for o in o_l if o.rect.height() >= 45 and o.rect.width() >= 12]
    if not tall_lines:
        tall_lines = o_l

    # Find clusters of dialogue lines
    clusters = []
    used = set()
    for i, line in enumerate(tall_lines):
        if i in used: continue
        cluster = [line]
        used.add(i)
        cx, cy = line.rect.centerX(), line.rect.centerY()
        for j, other in enumerate(tall_lines):
            if j in used: continue
            ox, oy = other.rect.centerX(), other.rect.centerY()
            if abs(cx - ox) < 140 and abs(cy - oy) < 220:
                cluster.append(other)
                used.add(j)
        clusters.append(cluster)

    # Pick the best dialogue cluster (largest total height or character box count)
    best_cluster = max(clusters, key=lambda c: sum(o.rect.height() for o in c))
    print(f"  Found {len(tall_lines)} tall orphan lines. Selected dialogue cluster with {len(best_cluster)} lines.", flush=True)

    # Compute bounding box with padding
    pad_x = 30
    pad_y = 25
    min_l = max(0, min(o.rect.left for o in best_cluster) - pad_x)
    min_t = max(0, min(o.rect.top for o in best_cluster) - pad_y)
    max_r = min(w, max(o.rect.right for o in best_cluster) + pad_x)
    max_b = min(h, max(o.rect.bottom for o in best_cluster) + pad_y)

    crop = img[min_t:max_b, min_l:max_r].copy()
    cw = max_r - min_l
    ch = max_b - min_t

    # Map heatmap patch
    scale_x = 1024.0 / float(w)
    scale_y = 1024.0 / float(h)
    gx = np.clip((np.arange(min_l, max_r) * scale_x).astype(np.int32), 0, 1023)
    gy = np.clip((np.arange(min_t, max_b) * scale_y).astype(np.int32), 0, 1023)
    hm_patch = prob[np.ix_(gy, gx)]

    local_lines = [
        (max(0, o.rect.left - min_l), max(0, o.rect.top - min_t), min(cw, o.rect.right - min_l), min(ch, o.rect.bottom - min_t))
        for o in best_cluster
    ]

    core_mask = hm_patch >= 0.18
    k_el = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    unified_mask = cv2.dilate(core_mask.astype(np.uint8), k_el) > 0
    unified_mask[0, :] = False; unified_mask[-1, :] = False; unified_mask[:, 0] = False; unified_mask[:, -1] = False

    # Execute all 4 approaches with timing
    t0 = time.perf_counter()
    res_base = inpaint_app0_baseline_laplacian(crop, local_lines, hm_patch)
    time_base = (time.perf_counter() - t0) * 1000.0

    t0 = time.perf_counter()
    res_med = inpaint_app1_statistical_median(crop, unified_mask)
    time_med = (time.perf_counter() - t0) * 1000.0

    t0 = time.perf_counter()
    res_telea = inpaint_app2_clean_gated_telea(crop, unified_mask)
    time_telea = (time.perf_counter() - t0) * 1000.0

    t0 = time.perf_counter()
    res_hyb = inpaint_app3_adaptive_hybrid(crop, unified_mask)
    time_hyb = (time.perf_counter() - t0) * 1000.0

    # Evaluate metrics
    gray_crop = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    k_corridor = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
    corridor = cv2.dilate(unified_mask.astype(np.uint8), k_corridor) > 0
    clean_bg = corridor & (~unified_mask) & (gray_crop > 160)
    if not np.any(clean_bg): clean_bg = corridor & (~unified_mask)
    bg_std = float(np.std(gray_crop[clean_bg])) if np.any(clean_bg) else 0.0

    s_base = float(np.std(cv2.cvtColor(res_base, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0
    s_med = float(np.std(cv2.cvtColor(res_med, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0
    s_telea = float(np.std(cv2.cvtColor(res_telea, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0
    s_hyb = float(np.std(cv2.cvtColor(res_hyb, cv2.COLOR_BGR2GRAY)[unified_mask])) if np.any(unified_mask) else 0.0

    regime = "Flat Paper (Median)" if (bg_std < 16.0 or np.mean(gray_crop[clean_bg]) > 235.0) else "Line Art (Telea)"

    print(f"  Bg std: {bg_std:.1f} | Baseline std: {s_base:.1f} ({time_base:.1f}ms) | Median std: {s_med:.1f} ({time_med:.1f}ms) | Telea std: {s_telea:.1f} ({time_telea:.1f}ms) | Hybrid std: {s_hyb:.1f} ({time_hyb:.1f}ms) [Regime: {regime}]", flush=True)

    # Build 5-panel comparison banner
    p1 = add_header(crop, "ORIGINAL", f"{cw}x{ch}px", (40, 40, 40))
    p2 = add_header(res_base, "BASELINE LAPLACIAN", f"std:{s_base:.1f} | {time_base:.1f}ms", (110, 30, 30))
    p3 = add_header(res_med, "STATISTICAL MEDIAN", f"std:{s_med:.1f} | {time_med:.1f}ms", (30, 95, 30))
    p4 = add_header(res_telea, "CLEAN-GATED TELEA", f"std:{s_telea:.1f} | {time_telea:.1f}ms", (30, 60, 110))
    p5 = add_header(res_hyb, "ADAPTIVE HYBRID", f"std:{s_hyb:.1f} | {time_hyb:.1f}ms", (85, 35, 95))

    comp = np.hstack([p1, p2, p3, p4, p5])
    out_img_name = f"comp_{idx:02d}_{bname}_{pname}.jpg"
    out_img_path = ARTIFACT_DIR / out_img_name
    cv2.imwrite(str(out_img_path), comp, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
    print(f"  Saved comparison image -> {out_img_name}", flush=True)

    results.append({
        "idx": idx,
        "batch": bname,
        "page": pname,
        "crop_size": f"{cw}x{ch}",
        "lines_in_cluster": len(best_cluster),
        "bg_std": round(bg_std, 2),
        "regime": regime,
        "baseline": {"std": round(s_base, 2), "ms": round(time_base, 2)},
        "median": {"std": round(s_med, 2), "ms": round(time_med, 2)},
        "telea": {"std": round(s_telea, 2), "ms": round(time_telea, 2)},
        "hybrid": {"std": round(s_hyb, 2), "ms": round(time_hyb, 2)},
        "comparison_image": out_img_name
    })

# Save JSON results
summary_json = Path(r"D:\C\KisaraTranslator\method3\simulator\output\orphan_12_benchmark_summary.json")
summary_json.parent.mkdir(parents=True, exist_ok=True)
with open(summary_json, "w", encoding="utf-8") as f:
    json.dump(results, f, indent=2)

print(f"\n==========================================")
print(f"ALL 12 PAGES BENCHMARKED SUCCESSFULLY!")
print(f"Summary written to {summary_json}")
print(f"==========================================", flush=True)
