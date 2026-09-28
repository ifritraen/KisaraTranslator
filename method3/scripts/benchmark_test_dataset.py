import os
import sys
import json
import glob
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort

if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if sys.stderr and hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

BASE_DIR = Path("D:/C/KisaraTranslator/method3")
MODEL_PATH = BASE_DIR / "trained_model" / "best.onnx"
TEST_DIR = BASE_DIR / "bubble_dataset" / "test"

session = ort.InferenceSession(str(MODEL_PATH), providers=['CPUExecutionProvider'])

def predict_single_image(img):
    h0, w0 = img.shape[:2]
    scale = min(640 / h0, 640 / w0)
    nw, nh = int(round(w0 * scale)), int(round(h0 * scale))
    canvas = np.full((640, 640, 3), 114, dtype=np.uint8)
    dx = (640 - nw) // 2
    dy = (640 - nh) // 2
    canvas[dy:dy+nh, dx:dx+nw] = cv2.resize(img, (nw, nh))
    
    inp = (canvas.astype(np.float32) / 255.0).transpose(2, 0, 1)[np.newaxis, ...]
    outputs = session.run(None, {'images': inp})[0][0]
    
    best_idx = np.argmax(np.maximum(outputs[4, :], outputs[5, :]))
    c0 = float(outputs[4, best_idx])
    c1 = float(outputs[5, best_idx])
    
    # Map keypoints back to original crop coords
    k0 = ((float(outputs[6, best_idx]) - dx) / scale, (float(outputs[7, best_idx]) - dy) / scale)
    k1 = ((float(outputs[9, best_idx]) - dx) / scale, (float(outputs[10, best_idx]) - dy) / scale)
    
    # Enforce canonical order: left-most or top-most first
    if k0[0] > k1[0] or (k0[0] == k1[0] and k0[1] > k1[1]):
        k0, k1 = k1, k0
        
    return {
        "pred_cls": 0 if c0 >= c1 else 1, # 0 = conjoined, 1 = single
        "conf_conj": c0,
        "conf_single": c1,
        "k0": k0,
        "k1": k1
    }

def snap_to_ink(img, pt, search_r=25):
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if len(img.shape) == 3 else img
    x0, y0 = int(round(pt[0])), int(round(pt[1]))
    x1, x2 = max(0, x0 - search_r), min(w, x0 + search_r + 1)
    y1, y2 = max(0, y0 - search_r), min(h, y0 + search_r + 1)
    roi = gray[y1:y2, x1:x2]
    dark_ys, dark_xs = np.where(roi < 100)
    if len(dark_xs) == 0:
        return pt
    min_d = 1e9
    best_p = pt
    for dx, dy in zip(dark_xs, dark_ys):
        gx, gy = x1 + dx, y1 + dy
        d = (gx - pt[0])**2 + (gy - pt[1])**2
        if d < min_d:
            min_d = d
            best_p = (float(gx), float(gy))
    return best_p

def evaluate_test_dataset():
    with open(TEST_DIR / "manifest.json", encoding="utf-8") as f:
        manifest = json.load(f)
        
    valid_annotated = [x for x in manifest if (x.get("status") == "verified" or x.get("is_conjoined") is not None) and not x.get("is_discarded")]
    print(f"Total non-discarded verified test samples: {len(valid_annotated)}")

    true_conjoined = [x for x in valid_annotated if x.get("is_conjoined") is True]
    true_single = [x for x in valid_annotated if x.get("is_conjoined") is False]

    print(f"  - Ground Truth Conjoined: {len(true_conjoined)}")
    print(f"  - Ground Truth Single:    {len(true_single)}")

    # Classification Metrics
    tp = 0 # GT conj, Pred conj
    fn = 0 # GT conj, Pred single
    tn = 0 # GT single, Pred single
    fp = 0 # GT single, Pred conj

    # Keypoint Distance Errors on Conjoined
    raw_kpt_errors = []
    snapped_kpt_errors = []
    
    # Check multi-cut stats in ground truth
    multi_cut_samples = 0
    single_cut_samples = 0

    img_dir = TEST_DIR / "images"
    annot_dir = TEST_DIR / "verified_annotated"

    for item in true_conjoined:
        b_id = item["bubble_id"]
        img_p = img_dir / item["filename"]
        img = cv2.imread(str(img_p))
        if img is None:
            continue
            
        pred = predict_single_image(img)
        if pred["pred_cls"] == 0:
            tp += 1
        else:
            fn += 1

        # Evaluate keypoints against first ground truth cutline
        json_p = annot_dir / f"{b_id}.json"
        if json_p.exists():
            with open(json_p, encoding="utf-8") as f:
                annot = json.load(f)
                
            pts = annot.get("crunch_points", [])
            lines = annot.get("dividing_lines", [])
            if len(lines) > 1 or len(pts) > 2:
                multi_cut_samples += 1
            else:
                single_cut_samples += 1

            if len(pts) >= 2:
                gt_c0 = (pts[0]["center"][0], pts[0]["center"][1])
                gt_c1 = (pts[1]["center"][0], pts[1]["center"][1])
                if gt_c0[0] > gt_c1[0] or (gt_c0[0] == gt_c1[0] and gt_c0[1] > gt_c1[1]):
                    gt_c0, gt_c1 = gt_c1, gt_c0
                    
                p0 = pred["k0"]
                p1 = pred["k1"]
                s0 = snap_to_ink(img, p0)
                s1 = snap_to_ink(img, p1)

                d0_raw = np.hypot(p0[0] - gt_c0[0], p0[1] - gt_c0[1])
                d1_raw = np.hypot(p1[0] - gt_c1[0], p1[1] - gt_c1[1])
                raw_kpt_errors.extend([d0_raw, d1_raw])

                d0_snap = np.hypot(s0[0] - gt_c0[0], s0[1] - gt_c0[1])
                d1_snap = np.hypot(s1[0] - gt_c1[0], s1[1] - gt_c1[1])
                snapped_kpt_errors.extend([d0_snap, d1_snap])

    for item in true_single:
        img_p = img_dir / item["filename"]
        img = cv2.imread(str(img_p))
        if img is None:
            continue
        pred = predict_single_image(img)
        if pred["pred_cls"] == 1:
            tn += 1
        else:
            fp += 1

    total_test = len(valid_annotated)
    accuracy = (tp + tn) / total_test * 100
    precision = tp / (tp + fp) * 100 if (tp + fp) > 0 else 0
    recall = tp / (tp + fn) * 100 if (tp + fn) > 0 else 0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0
    specificity = tn / (tn + fp) * 100 if (tn + fp) > 0 else 0

    results = {
        "total_evaluated": total_test,
        "gt_conjoined": len(true_conjoined),
        "gt_single": len(true_single),
        "tp": tp,
        "fn": fn,
        "tn": tn,
        "fp": fp,
        "accuracy": accuracy,
        "precision": precision,
        "recall": recall,
        "specificity": specificity,
        "f1": f1,
        "multi_cut_samples": multi_cut_samples,
        "single_cut_samples": single_cut_samples,
        "raw_median_err": float(np.median(raw_kpt_errors)) if raw_kpt_errors else 0,
        "raw_mean_err": float(np.mean(raw_kpt_errors)) if raw_kpt_errors else 0,
        "raw_within_10px": sum(e <= 10 for e in raw_kpt_errors) / len(raw_kpt_errors) * 100 if raw_kpt_errors else 0,
        "raw_within_20px": sum(e <= 20 for e in raw_kpt_errors) / len(raw_kpt_errors) * 100 if raw_kpt_errors else 0,
        "snapped_median_err": float(np.median(snapped_kpt_errors)) if snapped_kpt_errors else 0,
        "snapped_mean_err": float(np.mean(snapped_kpt_errors)) if snapped_kpt_errors else 0,
        "snapped_within_10px": sum(e <= 10 for e in snapped_kpt_errors) / len(snapped_kpt_errors) * 100 if snapped_kpt_errors else 0,
        "snapped_within_20px": sum(e <= 20 for e in snapped_kpt_errors) / len(snapped_kpt_errors) * 100 if snapped_kpt_errors else 0,
    }

    out_p = BASE_DIR / "scripts" / "test_benchmark_results.json"
    with open(out_p, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    print(json.dumps(results, indent=2))

if __name__ == "__main__":
    evaluate_test_dataset()
