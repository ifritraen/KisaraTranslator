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

with open(TEST_DIR / "manifest.json", encoding="utf-8") as f:
    items = json.load(f)

img_dir = TEST_DIR / "images"

# Target the 21 False Positives
fp_items = []
for it in items:
    if it.get("is_discarded") or it.get("is_conjoined") is not False:
        continue
    img_p = img_dir / it["filename"]
    img = cv2.imread(str(img_p))
    if img is None: continue
    pred = predict_single_image(img)
    if pred["pred_cls"] == 0:
        fp_items.append((it, pred, img))

print(f"Generating visual montage for {len(fp_items)} False Positives...")

# Create 3 grid montage images (7 items per image, or 3 rows of 7 = 21 items total)
# Each card will be normalized to 280x280 with dark padding
card_w, card_h = 280, 280

# Group into 3 images of 7
groups = [fp_items[i:i+7] for i in range(0, len(fp_items), 7)]

for g_idx, group in enumerate(groups, start=1):
    cols = len(group)
    canvas = np.full((card_h + 60, cols * card_w, 3), 26, dtype=np.uint8) # Dark background
    
    for c_idx, (it, pred, img) in enumerate(group):
        h0, w0 = img.shape[:2]
        
        # Draw predicted cutline on a copy
        vis = img.copy()
        p0 = (int(round(pred["k0"][0])), int(round(pred["k0"][1])))
        p1 = (int(round(pred["k1"][0])), int(round(pred["k1"][1])))
        s0 = snap_to_ink(img, pred["k0"])
        s1 = snap_to_ink(img, pred["k1"])
        s0_pt = (int(round(s0[0])), int(round(s0[1])))
        s1_pt = (int(round(s1[0])), int(round(s1[1])))
        
        # Raw line (dashed/thin orange)
        cv2.line(vis, p0, p1, (0, 165, 255), 2)
        # Snapped line (cyan)
        cv2.line(vis, s0_pt, s1_pt, (255, 255, 0), 3)
        cv2.circle(vis, s0_pt, 5, (0, 0, 255), -1)
        cv2.circle(vis, s1_pt, 5, (255, 0, 0), -1)
        
        # Fit into card_w x card_h keeping aspect ratio
        scale = min((card_w - 20) / w0, (card_h - 20) / h0)
        nw, nh = int(round(w0 * scale)), int(round(h0 * scale))
        resized = cv2.resize(vis, (nw, nh), interpolation=cv2.INTER_AREA)
        
        # Place centered in card
        x_off = c_idx * card_w + (card_w - nw) // 2
        y_off = 35 + (card_h - 20 - nh) // 2
        canvas[y_off:y_off+nh, x_off:x_off+nw] = resized
        
        # Card border
        cv2.rectangle(canvas, (c_idx * card_w, 0), ((c_idx + 1) * card_w, card_h + 60), (45, 45, 55), 1)
        
        # Title text
        b_id = it["bubble_id"]
        conf_str = f"Conj: {pred['conf_conj']:.2f}"
        cv2.putText(canvas, b_id, (c_idx * card_w + 10, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 229, 255), 1, cv2.LINE_AA)
        cv2.putText(canvas, conf_str, (c_idx * card_w + 140, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (255, 138, 128), 1, cv2.LINE_AA)

    out_file = ARTIFACTS_DIR / f"false_positives_part{g_idx}.png"
    cv2.imwrite(str(out_file), canvas)
    print(f"Saved: {out_file}")

print("Done generating false positive montages!")
