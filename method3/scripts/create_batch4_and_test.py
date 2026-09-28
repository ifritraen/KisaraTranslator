import os
import sys
import shutil
import json
import random
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort

if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if sys.stderr and hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

BASE_DIR = Path("d:/C/KisaraTranslator/method3")
PAGES_ALL_DIR = BASE_DIR / "raw_manga_batch_04" / "extracted_pages"
PAGES_WITH_BUBBLES_DIR = BASE_DIR / "raw_manga_batch_04" / "pages_with_bubbles"
BATCH_04_DIR = BASE_DIR / "bubble_dataset" / "batch_04"
TEST_DIR = BASE_DIR / "bubble_dataset" / "test"
MODEL_PATH = BASE_DIR / "models" / "manga109_segmentation_bubble_1024.onnx"

def compute_adaptive_padding(bw: int, bh: int) -> int:
    dim = max(bw, bh)
    pad = int(np.clip(round(10 + (dim - 150) * (4.0 / 350.0)), 10, 14))
    return pad

class BubbleSegmenter:
    def __init__(self, model_path: str):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(str(model_path), opts)
        self.input_name = self.session.get_inputs()[0].name
        self.input_size = 1024

    def detect(self, image: np.ndarray, conf_thresh: float = 0.35, iou_thresh: float = 0.45):
        orig_h, orig_w = image.shape[:2]
        resized = cv2.resize(image, (self.input_size, self.input_size))
        blob = cv2.dnn.blobFromImage(resized, 1.0 / 255.0, (self.input_size, self.input_size), swapRB=True)
        
        outputs = self.session.run(None, {self.input_name: blob})
        o0 = outputs[0][0]
        protos = outputs[1][0]
        
        confidences = o0[4, :]
        valid_indices = np.where(confidences >= conf_thresh)[0]
        if len(valid_indices) == 0:
            return []
            
        raw_candidates = []
        for idx in valid_indices:
            cx, cy, bw, bh = o0[0:4, idx]
            conf = float(confidences[idx])
            x1 = max(0, int((cx - bw / 2.0) * orig_w / self.input_size))
            y1 = max(0, int((cy - bh / 2.0) * orig_h / self.input_size))
            x2 = min(orig_w, int((cx + bw / 2.0) * orig_w / self.input_size))
            y2 = min(orig_h, int((cy + bh / 2.0) * orig_h / self.input_size))
            if (x2 - x1) >= 20 and (y2 - y1) >= 20:
                raw_candidates.append((x1, y1, x2, y2, conf, idx))
                
        raw_candidates.sort(key=lambda item: item[4], reverse=True)
        kept_candidates = []
        for cand in raw_candidates:
            x1, y1, x2, y2, conf, idx = cand
            suppress = False
            for kept in kept_candidates:
                kx1, ky1, kx2, ky2, _, _ = kept
                ix1, iy1 = max(x1, kx1), max(y1, ky1)
                ix2, iy2 = min(x2, kx2), min(y2, ky2)
                if ix2 > ix1 and iy2 > iy1:
                    inter = (ix2 - ix1) * (iy2 - iy1)
                    union = (x2 - x1) * (y2 - y1) + (kx2 - kx1) * (ky2 - ky1) - inter
                    iou = inter / union if union > 0 else 0
                    smaller_area = min((x2 - x1) * (y2 - y1), (kx2 - kx1) * (ky2 - ky1))
                    containment = inter / smaller_area if smaller_area > 0 else 0
                    if iou > iou_thresh or containment > 0.70:
                        suppress = True
                        break
            if not suppress:
                kept_candidates.append(cand)
                
        proto_flat = protos.reshape(32, -1)
        results = []
        for cand in kept_candidates:
            x1, y1, x2, y2, conf, idx = cand
            pad = compute_adaptive_padding(x2 - x1, y2 - y1)
            crop_x1 = max(0, x1 - pad)
            crop_y1 = max(0, y1 - pad)
            crop_x2 = min(orig_w, x2 + pad)
            crop_y2 = min(orig_h, y2 + pad)
            
            coeffs = o0[5:37, idx]
            mask_256 = np.dot(coeffs, proto_flat).reshape(256, 256)
            mask_sigmoid = 1.0 / (1.0 + np.exp(-mask_256))
            mask_orig = cv2.resize(mask_sigmoid, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
            bin_mask = (mask_orig > 0.50).astype(np.uint8)
            
            area = int(np.sum(bin_mask[y1:y2, x1:x2]))
            
            results.append({
                "raw_bbox": [x1, y1, x2, y2],
                "crop_bbox": [crop_x1, crop_y1, crop_x2, crop_y2],
                "padding": pad,
                "conf": conf,
                "area": area
            })
            
        results.sort(key=lambda b: (b["raw_bbox"][1] // 150, -b["raw_bbox"][0]))
        return results

def init_batch_dirs(target_dir: Path):
    for sub in ["images", "non_annotated", "annotations", "verified_annotated", "conjoined", "unverified_annotated", "recheck_annotated", "discarded"]:
        (target_dir / sub).mkdir(parents=True, exist_ok=True)

def process_batch4_and_test():
    print("="*65)
    print("STEP 1: Full scan of all 958 pages across all 14 manga books")
    print("="*65)
    
    PAGES_WITH_BUBBLES_DIR.mkdir(parents=True, exist_ok=True)
    init_batch_dirs(BATCH_04_DIR)
    init_batch_dirs(TEST_DIR)

    segmenter = BubbleSegmenter(str(MODEL_PATH))
    valid_exts = {".jpg", ".jpeg", ".png", ".webp"}
    all_pages = sorted([p for p in PAGES_ALL_DIR.iterdir() if p.suffix.lower() in valid_exts])
    print(f"Total extracted pages to evaluate: {len(all_pages)}")

    b4_images_dir = BATCH_04_DIR / "images"
    b4_non_annot_dir = BATCH_04_DIR / "non_annotated"
    
    manifest_b4 = []
    total_b4_bubbles = 0
    pages_with_bubbles_count = 0
    pages_filtered_out = 0

    for idx, page_path in enumerate(all_pages, start=1):
        img = cv2.imread(str(page_path))
        if img is None:
            continue
            
        bubbles = segmenter.detect(img, conf_thresh=0.35)
        if len(bubbles) == 0:
            pages_filtered_out += 1
            if idx % 100 == 0:
                print(f"[{idx}/{len(all_pages)}] Filtered 0-bubble page: {page_path.name}")
            continue
            
        pages_with_bubbles_count += 1
        raw_dest = PAGES_WITH_BUBBLES_DIR / page_path.name
        if not raw_dest.exists():
            shutil.copy2(page_path, raw_dest)
            
        h, w = img.shape[:2]
        if pages_with_bubbles_count % 50 == 0 or idx == len(all_pages) or pages_with_bubbles_count <= 3:
            print(f"[{idx}/{len(all_pages)}] Page #{pages_with_bubbles_count} ({page_path.name}): {len(bubbles)} bubbles (Total so far: {total_b4_bubbles + len(bubbles)})")

        for b_idx, bubble in enumerate(bubbles, start=1):
            total_b4_bubbles += 1
            x1, y1, x2, y2 = bubble["raw_bbox"]
            crop_x1, crop_y1, crop_x2, crop_y2 = bubble["crop_bbox"]
            
            crop_img = img[crop_y1:crop_y2, crop_x1:crop_x2].copy()
            
            bubble_id = f"batch_04_b{total_b4_bubbles:05d}"
            filename = f"{bubble_id}_{page_path.stem}_b{b_idx:02d}.png"
            
            cv2.imwrite(str(b4_images_dir / filename), crop_img)
            cv2.imwrite(str(b4_non_annot_dir / filename), crop_img)
            
            manifest_b4.append({
                "bubble_id": bubble_id,
                "batch": "batch_04",
                "filename": filename,
                "page_name": page_path.name,
                "page_index": pages_with_bubbles_count,
                "bubble_index_on_page": b_idx,
                "page_dimensions": {"width": w, "height": h},
                "raw_bbox": [x1, y1, x2, y2],
                "crop_bbox": [crop_x1, crop_y1, crop_x2, crop_y2],
                "crop_dimensions": {"width": int(crop_x2 - crop_x1), "height": int(crop_y2 - crop_y1)},
                "padding_used": bubble["padding"],
                "confidence": round(float(bubble["conf"]), 4),
                "mask_area_px": int(bubble["area"]),
                "is_conjoined": None,
                "is_discarded": False,
                "discard_reason": None,
                "status": "unlabeled"
            })

    # Save Batch 4 full manifest
    b4_manifest_p = BATCH_04_DIR / "manifest.json"
    with open(b4_manifest_p, "w", encoding="utf-8") as f:
        json.dump(manifest_b4, f, indent=2)

    print("\n" + "="*65)
    print("BATCH 4 (COMPLETE EXTRACTION) READY:")
    print(f"  Total Pages Scanned:         {len(all_pages)}")
    print(f"  Pages Filtered Out:          {pages_filtered_out}")
    print(f"  Pages With Bubbles:          {pages_with_bubbles_count}")
    print(f"  Total Batch 4 Bubbles:       {total_b4_bubbles}")
    print("="*65)

    # -------------------------------------------------------------
    # STEP 2: Randomly sample 1000 bubbles from Batch 4 to create 'test'
    # -------------------------------------------------------------
    print("\n" + "="*65)
    print("STEP 2: Creating 'test' dataset (1000 random bubbles from Batch 4)")
    print("="*65)
    
    random.seed(42)
    sample_count = min(1000, len(manifest_b4))
    sampled_indices = sorted(random.sample(range(len(manifest_b4)), sample_count))
    
    test_images_dir = TEST_DIR / "images"
    test_non_annot_dir = TEST_DIR / "non_annotated"
    
    manifest_test = []
    for t_idx, orig_idx in enumerate(sampled_indices, start=1):
        orig_item = manifest_b4[orig_idx]
        
        test_bubble_id = f"test_b{t_idx:05d}"
        orig_filename = orig_item["filename"]
        test_filename = f"{test_bubble_id}_{orig_filename.split('_', 2)[-1]}"
        
        # Copy image file
        src_img_p = b4_images_dir / orig_filename
        dst_img_p = test_images_dir / test_filename
        dst_non_p = test_non_annot_dir / test_filename
        
        shutil.copy2(src_img_p, dst_img_p)
        shutil.copy2(src_img_p, dst_non_p)
        
        test_item = dict(orig_item)
        test_item["bubble_id"] = test_bubble_id
        test_item["batch"] = "test"
        test_item["filename"] = test_filename
        test_item["status"] = "unlabeled"
        test_item["is_conjoined"] = None
        test_item["is_discarded"] = False
        test_item["original_batch4_id"] = orig_item["bubble_id"]
        
        manifest_test.append(test_item)
        
    test_manifest_p = TEST_DIR / "manifest.json"
    with open(test_manifest_p, "w", encoding="utf-8") as f:
        json.dump(manifest_test, f, indent=2)

    print(f"SUCCESSFULLY GENERATED 'test' BATCH:")
    print(f"  Total Random Test Bubbles:   {len(manifest_test)}")
    print(f"  Location:                    {TEST_DIR}")
    print(f"  Manifest:                    {test_manifest_p}")
    print("="*65)

if __name__ == "__main__":
    process_batch4_and_test()
