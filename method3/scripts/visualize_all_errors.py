import json
from pathlib import Path
import cv2
import numpy as np
import sys
sys.path.append('D:/C/KisaraTranslator/method3/scripts')
from benchmark_test_dataset import predict_single_image, snap_to_ink

BASE_DIR = Path("D:/C/KisaraTranslator/method3")
TEST_DIR = BASE_DIR / "bubble_dataset" / "test"
ARTIFACTS_DIR = Path("C:/Users/akhla/.gemini/antigravity/brain/c1a6996f-2204-4cd2-aa4e-a1f8b4571df5")
img_dir = TEST_DIR / "images"
annot_dir = TEST_DIR / "verified_annotated"

with open(TEST_DIR / "manifest.json", encoding="utf-8") as f:
    items = json.load(f)

fp_items = []
fn_items = []

for it in items:
    if it.get("is_discarded") or it.get("is_conjoined") is None:
        continue
    img_p = img_dir / it["filename"]
    img = cv2.imread(str(img_p))
    if img is None: continue
    pred = predict_single_image(img)
    
    # False Positive: Ground truth single, model said conjoined
    if it.get("is_conjoined") is False and pred["pred_cls"] == 0:
        fp_items.append((it, pred, img))
    # False Negative: Ground truth conjoined, model said single
    elif it.get("is_conjoined") is True and pred["pred_cls"] == 1:
        fn_items.append((it, pred, img))

print(f"Rendering: {len(fp_items)} False Positives + {len(fn_items)} False Negatives...")

# Render False Negatives Panel (with ground truth green line vs model prediction)
card_w, card_h = 320, 320
fn_canvas = np.full((card_h + 70, len(fn_items) * card_w, 3), 26, dtype=np.uint8)

for c_idx, (it, pred, img) in enumerate(fn_items):
    vis = img.copy()
    h0, w0 = img.shape[:2]
    
    # Load Ground Truth cutline
    json_p = annot_dir / f"{it['bubble_id']}.json"
    if json_p.exists():
        with open(json_p, encoding="utf-8") as f:
            annot = json.load(f)
        pts = annot.get("crunch_points", [])
        if len(pts) >= 2:
            pA = (pts[0]["center"][0], pts[0]["center"][1])
            pB = (pts[1]["center"][0], pts[1]["center"][1])
            # Draw Ground Truth Cut in Green
            cv2.line(vis, pA, pB, (0, 255, 0), 3)
            cv2.circle(vis, pA, 6, (0, 255, 0), -1)
            cv2.circle(vis, pB, 6, (0, 255, 0), -1)

    scale = min((card_w - 20) / w0, (card_h - 20) / h0)
    nw, nh = int(round(w0 * scale)), int(round(h0 * scale))
    resized = cv2.resize(vis, (nw, nh), interpolation=cv2.INTER_AREA)
    
    x_off = c_idx * card_w + (card_w - nw) // 2
    y_off = 40 + (card_h - 20 - nh) // 2
    fn_canvas[y_off:y_off+nh, x_off:x_off+nw] = resized
    
    cv2.rectangle(fn_canvas, (c_idx * card_w, 0), ((c_idx + 1) * card_w, card_h + 70), (60, 40, 40), 1)
    b_id = it["bubble_id"]
    cv2.putText(fn_canvas, f"FN: {b_id}", (c_idx * card_w + 10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 255, 128), 1, cv2.LINE_AA)
    cv2.putText(fn_canvas, f"Single: {pred['conf_single']:.2f}", (c_idx * card_w + 170, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (255, 100, 100), 1, cv2.LINE_AA)

fn_out = ARTIFACTS_DIR / "false_negatives.png"
cv2.imwrite(str(fn_out), fn_canvas)
print(f"Saved: {fn_out}")

# Render False Positives in 3 rows (7 items each)
groups = [fp_items[i:i+7] for i in range(0, len(fp_items), 7)]
for g_idx, group in enumerate(groups, start=1):
    cols = len(group)
    canvas = np.full((card_h + 60, cols * card_w, 3), 26, dtype=np.uint8)
    for c_idx, (it, pred, img) in enumerate(group):
        h0, w0 = img.shape[:2]
        vis = img.copy()
        s0 = snap_to_ink(img, pred["k0"])
        s1 = snap_to_ink(img, pred["k1"])
        s0_pt = (int(round(s0[0])), int(round(s0[1])))
        s1_pt = (int(round(s1[0])), int(round(s1[1])))
        cv2.line(vis, s0_pt, s1_pt, (255, 255, 0), 3) # Cyan predicted cut
        cv2.circle(vis, s0_pt, 6, (0, 0, 255), -1)
        cv2.circle(vis, s1_pt, 6, (255, 0, 0), -1)
        
        scale = min((card_w - 20) / w0, (card_h - 20) / h0)
        nw, nh = int(round(w0 * scale)), int(round(h0 * scale))
        resized = cv2.resize(vis, (nw, nh), interpolation=cv2.INTER_AREA)
        
        x_off = c_idx * card_w + (card_w - nw) // 2
        y_off = 35 + (card_h - 20 - nh) // 2
        canvas[y_off:y_off+nh, x_off:x_off+nw] = resized
        
        cv2.rectangle(canvas, (c_idx * card_w, 0), ((c_idx + 1) * card_w, card_h + 60), (45, 45, 55), 1)
        b_id = it["bubble_id"]
        cv2.putText(canvas, f"FP: {b_id}", (c_idx * card_w + 10, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 229, 255), 1, cv2.LINE_AA)
        cv2.putText(canvas, f"Conj: {pred['conf_conj']:.2f}", (c_idx * card_w + 160, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (255, 138, 128), 1, cv2.LINE_AA)
        
    fp_out = ARTIFACTS_DIR / f"false_positives_part{g_idx}.png"
    cv2.imwrite(str(fp_out), canvas)
    print(f"Saved: {fp_out}")

print("All error visual montages generated successfully!")
