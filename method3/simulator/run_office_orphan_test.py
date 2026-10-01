import os
import sys
import time
import json
import cv2
import numpy as np
from pathlib import Path

# Ensure stdout uses utf-8
if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

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

print("Loading models...", flush=True)
ctd = ComicTextDetector(CTD_MODEL)
seg = BubbleSegmenter(SEG_MODEL)

RAW_DIR = Path(r"D:\C\KisaraTranslator\.aaa\SigmaTest\datasets\batch_01_office\raw")
ARTIFACT_DIR = Path(r"C:\Users\akhla\.gemini\antigravity\brain\779c9e56-e0ab-42e3-a952-9e1be7ff2e16")
ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)

TARGET_PAGES = [
    {
        "id": "office_chap2",
        "title": "Chapter 2",
        "path": RAW_DIR / "[いちのせ] 気になってた同期 つづき - Chapter - 2.jpg"
    },
    {
        "id": "office_chap3",
        "title": "Chapter 3",
        "path": RAW_DIR / "[いちのせ] 気になってた同期 つづき - Chapter - 3.jpg"
    }
]

def cv2_imread_unicode(filepath: str) -> np.ndarray:
    with open(filepath, "rb") as f:
        data = np.frombuffer(f.read(), dtype=np.uint8)
        return cv2.imdecode(data, cv2.IMREAD_COLOR)

def cv2_imwrite_unicode(filepath: str, img: np.ndarray, params=None) -> bool:
    ext = Path(filepath).suffix
    success, encoded = cv2.imencode(ext, img, params)
    if success:
        with open(filepath, "wb") as f:
            f.write(encoded)
        return True
    return False

def add_header(img: np.ndarray, title: str, subtitle: str, color=(35, 35, 35)) -> np.ndarray:
    h, w = 46, img.shape[1]
    banner = np.full((h, w, 3), color, dtype=np.uint8)
    font = cv2.FONT_HERSHEY_SIMPLEX
    
    (tw1, th1), _ = cv2.getTextSize(title, font, 0.42, 1)
    tx1 = max(4, (w - tw1) // 2)
    cv2.putText(banner, title, (tx1, 18), font, 0.42, (255, 255, 255), 1, cv2.LINE_AA)
    
    if subtitle:
        (tw2, th2), _ = cv2.getTextSize(subtitle, font, 0.36, 1)
        tx2 = max(4, (w - tw2) // 2)
        cv2.putText(banner, subtitle, (tx2, 36), font, 0.36, (200, 225, 255), 1, cv2.LINE_AA)
        
    return np.vstack([banner, img])

results = []

for pinfo in TARGET_PAGES:
    pid = pinfo["id"]
    title = pinfo["title"]
    ppath = str(pinfo["path"])

    if not os.path.exists(ppath):
        print(f"Error: File not found: {ppath}", flush=True)
        continue

    print(f"\n==========================================", flush=True)
    print(f"Processing {title} -> {Path(ppath).name}", flush=True)
    print(f"==========================================", flush=True)

    img = cv2_imread_unicode(ppath)
    if img is None:
        print(f"Error: Failed to read image: {ppath}", flush=True)
        continue

    h, w = img.shape[:2]
    print(f"Image dimensions: {w}x{h} px", flush=True)

    # Step 1: Detect CTD boxes and speech bubble masks
    t0 = time.perf_counter()
    m1_b, ctd_b, prob = ctd.run_module1(img)
    ctd_time = (time.perf_counter() - t0) * 1000.0

    t0 = time.perf_counter()
    masks = seg.detect_masks(img)
    seg_time = (time.perf_counter() - t0) * 1000.0

    # Step 2: Categorization (with ctd_bubbles!) and Line Stitching
    all_cat, b_b, o_b, s_b, dist = TextCategorizer.categorize_boxes(
        m1_b, [], masks, img, w, h, ctd_bubbles=ctd_b, is_rtl=True
    )
    all_l, b_l, o_l, s_l, f = VerticalLineStitcher.stitch_lines(
        all_cat, dist, masks, img, w, h, is_rtl=True
    )

    print(f"Detected: CTD={len(m1_b)}, Bubbles={len(masks)}, CTD_Bubbles={len(ctd_b)}", flush=True)
    print(f"Lines Stitched: Bubbled={len(b_l)}, Orphan={len(o_l)}, SFX={len(s_l)}", flush=True)

    if not o_l:
        print(f"Notice: No orphan lines found on {title}! Checking all lines...", flush=True)
        continue

    # Cluster orphan lines spatially
    clusters = []
    used = set()
    for i, line in enumerate(o_l):
        if i in used: continue
        cluster = [line]
        used.add(i)
        cx, cy = line.rect.centerX(), line.rect.centerY()
        for j, other in enumerate(o_l):
            if j in used: continue
            ox, oy = other.rect.centerX(), other.rect.centerY()
            if abs(cx - ox) < 160 and abs(cy - oy) < 260:
                cluster.append(other)
                used.add(j)
        clusters.append(cluster)

    print(f"Found {len(clusters)} orphan dialogue cluster(s).", flush=True)

    # Process each cluster
    for c_idx, cluster in enumerate(clusters):
        c_lines_count = len(cluster)
        total_h = sum(o.rect.height() for o in cluster)
        print(f"\n--- Cluster #{c_idx + 1} ({c_lines_count} line(s), total height: {total_h}px) ---", flush=True)

        pad_x = 35
        pad_y = 30
        min_l = max(0, min(o.rect.left for o in cluster) - pad_x)
        min_t = max(0, min(o.rect.top for o in cluster) - pad_y)
        max_r = min(w, max(o.rect.right for o in cluster) + pad_x)
        max_b = min(h, max(o.rect.bottom for o in cluster) + pad_y)

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

        if not np.any(unified_mask):
            print("  Warning: No core text ink in cluster crop!")
            continue

        # Approach 0: Baseline Laplacian
        t_start = time.perf_counter()
        res_base = inpaint_app0_baseline_laplacian(crop, local_lines, hm_patch)
        t_base = (time.perf_counter() - t_start) * 1000.0

        # Approach 1: Statistical Median
        t_start = time.perf_counter()
        res_med = inpaint_app1_statistical_median(crop, unified_mask)
        t_med = (time.perf_counter() - t_start) * 1000.0

        # Approach 2: Clean-Gated Telea
        t_start = time.perf_counter()
        res_telea = inpaint_app2_clean_gated_telea(crop, unified_mask)
        t_telea = (time.perf_counter() - t_start) * 1000.0

        # Approach 3: Two-Regime Adaptive Hybrid
        t_start = time.perf_counter()
        res_hyb = inpaint_app3_adaptive_hybrid(crop, unified_mask)
        t_hyb = (time.perf_counter() - t_start) * 1000.0

        # Metrics
        gray_crop = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        k_corridor = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
        corridor = cv2.dilate(unified_mask.astype(np.uint8), k_corridor) > 0
        clean_bg = corridor & (~unified_mask) & (gray_crop > 160)
        if not np.any(clean_bg): clean_bg = corridor & (~unified_mask)
        bg_std = float(np.std(gray_crop[clean_bg])) if np.any(clean_bg) else 0.0

        s_base = float(np.std(cv2.cvtColor(res_base, cv2.COLOR_BGR2GRAY)[unified_mask]))
        s_med = float(np.std(cv2.cvtColor(res_med, cv2.COLOR_BGR2GRAY)[unified_mask]))
        s_telea = float(np.std(cv2.cvtColor(res_telea, cv2.COLOR_BGR2GRAY)[unified_mask]))
        s_hyb = float(np.std(cv2.cvtColor(res_hyb, cv2.COLOR_BGR2GRAY)[unified_mask]))

        regime = "Flat Paper (Median)" if (bg_std < 16.0 or np.mean(gray_crop[clean_bg]) > 235.0) else "Line Art (Telea)"

        print(f"  Bg std: {bg_std:.1f} | Regime: {regime}", flush=True)
        print(f"  Baseline: std={s_base:.1f} ({t_base:.1f}ms)", flush=True)
        print(f"  Median:   std={s_med:.1f} ({t_med:.1f}ms)", flush=True)
        print(f"  Telea:    std={s_telea:.1f} ({t_telea:.1f}ms)", flush=True)
        print(f"  Hybrid:   std={s_hyb:.1f} ({t_hyb:.1f}ms)", flush=True)

        # Build 5-panel comparison banner
        p1 = add_header(crop, "ORIGINAL", f"{cw}x{ch}px", (40, 40, 40))
        p2 = add_header(res_base, "BASELINE LAPLACIAN", f"std:{s_base:.1f} | {t_base:.1f}ms", (110, 30, 30))
        p3 = add_header(res_med, "STATISTICAL MEDIAN", f"std:{s_med:.1f} | {t_med:.1f}ms", (30, 95, 30))
        p4 = add_header(res_telea, "CLEAN-GATED TELEA", f"std:{s_telea:.1f} | {t_telea:.1f}ms", (30, 60, 110))
        p5 = add_header(res_hyb, "ADAPTIVE HYBRID", f"std:{s_hyb:.1f} | {t_hyb:.1f}ms", (85, 35, 95))

        comp = np.hstack([p1, p2, p3, p4, p5])
        comp_filename = f"{pid}_cluster{c_idx + 1}_comparison.jpg"
        out_comp_path = ARTIFACT_DIR / comp_filename
        cv2.imwrite(str(out_comp_path), comp, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
        print(f"  -> Saved comparison image: {comp_filename}", flush=True)

        results.append({
            "page_id": pid,
            "title": title,
            "cluster_idx": c_idx + 1,
            "lines_count": c_lines_count,
            "crop_size": f"{cw}x{ch}",
            "bg_std": round(bg_std, 2),
            "regime": regime,
            "baseline": {"std": round(s_base, 2), "ms": round(t_base, 2)},
            "median": {"std": round(s_med, 2), "ms": round(t_med, 2)},
            "telea": {"std": round(s_telea, 2), "ms": round(t_telea, 2)},
            "hybrid": {"std": round(s_hyb, 2), "ms": round(t_hyb, 2)},
            "comparison_image": comp_filename
        })

summary_path = ARTIFACT_DIR / "office_test_summary.json"
with open(summary_path, "w", encoding="utf-8") as f:
    json.dump(results, f, indent=2)

print("\n==========================================", flush=True)
print(f"OFFICE TEST COMPLETE! Total clusters tested: {len(results)}", flush=True)
print(f"Summary written to {summary_path}", flush=True)
print("==========================================", flush=True)
