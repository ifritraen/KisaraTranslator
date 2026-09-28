import os
import sys
import subprocess
import shutil
import zipfile
import json
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort

ADB_DEVICE = "adb-RF8R319PD1M-A3k1sx._adb-tls-connect._tcp"
BASE_DIR = Path("d:/C/KisaraTranslator/method3")
CBZ_DIR = BASE_DIR / "raw_manga_batch_02" / "cbz"
PAGES_ALL_DIR = BASE_DIR / "raw_manga_batch_02" / "extracted_pages"
PAGES_WITH_BUBBLES_DIR = BASE_DIR / "raw_manga_batch_02" / "pages_with_bubbles"
BATCH_OUTPUT = BASE_DIR / "bubble_dataset" / "batch_02"
MODEL_PATH = BASE_DIR / "models" / "manga109_segmentation_bubble_1024.onnx"

def compute_adaptive_padding(bw: int, bh: int) -> int:
    dim = max(bw, bh)
    pad = int(np.clip(round(12 + (dim - 150) * (4.0 / 350.0)), 12, 16))
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

def step1_pull_cbz():
    print("\n--- STEP 1: Pulling CBZ files from Mobile Device ---")
    CBZ_DIR.mkdir(parents=True, exist_ok=True)
    
    proc = subprocess.run(
        ["adb", "-s", ADB_DEVICE, "shell", "find /sdcard/Aaaaa/Otaku/Komikku/downloads -name '*.cbz'"],
        capture_output=True
    )
    raw_output = proc.stdout.decode("utf-8", errors="replace")
    remote_files = [line.strip() for line in raw_output.splitlines() if line.strip().endswith(".cbz")]
    print(f"Found {len(remote_files)} remote CBZ files on mobile device.")
    
    metadata = {}
    pulled = 0
    for idx, remote_path in enumerate(remote_files, start=1):
        safe_id = f"cbz_{idx:02d}.cbz"
        local_path = CBZ_DIR / safe_id
        
        rel_info = remote_path.replace("/sdcard/Aaaaa/Otaku/Komikku/downloads/", "")
        metadata[safe_id] = {
            "remote_path": remote_path,
            "title_info": rel_info
        }
        
        if local_path.exists() and local_path.stat().st_size > 10000:
            print(f"[{idx}/{len(remote_files)}] Already downloaded: {safe_id}")
            pulled += 1
            continue
            
        print(f"[{idx}/{len(remote_files)}] Pulling {safe_id} ({remote_path[:30]}...)...")
        res = subprocess.run(
            ["adb", "-s", ADB_DEVICE, "pull", remote_path, str(local_path)],
            capture_output=True
        )
        if res.returncode == 0:
            pulled += 1
        else:
            print(f"Error pulling: {res.stderr.decode('utf-8', errors='replace')}")
            
    with open(CBZ_DIR / "metadata.json", "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2, ensure_ascii=False)
        
    print(f"Successfully secured {pulled} CBZ files in {CBZ_DIR}")

def step2_extract_pages():
    print("\n--- STEP 2: Extracting Manga Pages from CBZ archives ---")
    PAGES_ALL_DIR.mkdir(parents=True, exist_ok=True)
    
    cbz_files = sorted(list(CBZ_DIR.glob("cbz_*.cbz")))
    valid_exts = {".jpg", ".jpeg", ".png", ".webp"}
    total_pages = 0
    
    for c_idx, cbz_file in enumerate(cbz_files, start=1):
        try:
            with zipfile.ZipFile(cbz_file, "r") as zf:
                image_entries = [name for name in zf.namelist() if Path(name).suffix.lower() in valid_exts]
                image_entries.sort()
                for p_idx, entry in enumerate(image_entries, start=1):
                    ext = Path(entry).suffix.lower()
                    target_filename = f"c{c_idx:02d}_p{p_idx:03d}{ext}"
                    target_path = PAGES_ALL_DIR / target_filename
                    if not target_path.exists() or target_path.stat().st_size == 0:
                        with zf.open(entry) as src, open(target_path, "wb") as dst:
                            dst.write(src.read())
                    total_pages += 1
        except Exception as e:
            print(f"Error extracting {cbz_file.name}: {e}")
            
    print(f"Total extracted manga pages across all titles: {total_pages} in {PAGES_ALL_DIR}")

def step3_filter_and_extract_bubbles():
    print("\n--- STEP 3: Detecting Bubbles, Filtering Empty Pages, and Slicing Crops ---")
    PAGES_WITH_BUBBLES_DIR.mkdir(parents=True, exist_ok=True)
    images_output = BATCH_OUTPUT / "images"
    non_annotated_output = BATCH_OUTPUT / "non_annotated"
    
    for sub in ["images", "non_annotated", "annotations", "verified_annotated", "conjoined", "unverified_annotated", "recheck_annotated", "discarded"]:
        folder = BATCH_OUTPUT / sub
        folder.mkdir(parents=True, exist_ok=True)
        
    print(f"Loading Manga109 ONNX segmenter: {MODEL_PATH}")
    segmenter = BubbleSegmenter(str(MODEL_PATH))
    
    valid_exts = {".jpg", ".jpeg", ".png", ".webp"}
    all_pages = sorted([p for p in PAGES_ALL_DIR.iterdir() if p.suffix.lower() in valid_exts])
    print(f"Scanning {len(all_pages)} total extracted pages...")
    
    manifest = []
    total_bubbles = 0
    pages_with_bubbles_count = 0
    pages_filtered_out = 0
    
    for idx, page_path in enumerate(all_pages, start=1):
        img = cv2.imread(str(page_path))
        if img is None:
            continue
            
        h, w = img.shape[:2]
        bubbles = segmenter.detect(img, conf_thresh=0.35)
        
        if len(bubbles) == 0:
            pages_filtered_out += 1
            if idx % 50 == 0 or idx == len(all_pages):
                print(f"[{idx}/{len(all_pages)}] Filtered out (0 bubbles): {page_path.name}")
            continue
            
        # Page has bubbles!
        pages_with_bubbles_count += 1
        # Save page to Raw for batch 2
        raw_page_dest = PAGES_WITH_BUBBLES_DIR / page_path.name
        if not raw_page_dest.exists():
            shutil.copy2(page_path, raw_page_dest)
            
        if pages_with_bubbles_count % 25 == 0 or idx == len(all_pages) or pages_with_bubbles_count <= 5:
            print(f"[{idx}/{len(all_pages)}] Page #{pages_with_bubbles_count}: {page_path.name} -> {len(bubbles)} bubbles (Total: {total_bubbles + len(bubbles)})")
            
        for b_idx, bubble in enumerate(bubbles, start=1):
            total_bubbles += 1
            x1, y1, x2, y2 = bubble["raw_bbox"]
            crop_x1, crop_y1, crop_x2, crop_y2 = bubble["crop_bbox"]
            
            # Natural pixel crop (12-16px padding)
            crop_img = img[crop_y1:crop_y2, crop_x1:crop_x2].copy()
            
            bubble_id = f"batch_02_b{total_bubbles:05d}"
            filename = f"{bubble_id}_{page_path.stem}_b{b_idx:02d}.png"
            
            cv2.imwrite(str(images_output / filename), crop_img)
            cv2.imwrite(str(non_annotated_output / filename), crop_img)
            
            manifest.append({
                "bubble_id": bubble_id,
                "batch": "batch_02",
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
            
    manifest_path = BATCH_OUTPUT / "manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        
    print("\n" + "="*60)
    print("BATCH 2 EXTRACTION & FILTERING COMPLETE:")
    print(f"  Total Pages Scanned:         {len(all_pages)}")
    print(f"  Pages Filtered (0 bubbles):  {pages_filtered_out}")
    print(f"  Raw Pages With Bubbles:      {pages_with_bubbles_count}")
    print(f"  Total Bubbles Sliced:        {total_bubbles}")
    print(f"  Manifest Saved:              {manifest_path}")
    print("="*60)

if __name__ == "__main__":
    step1_pull_cbz()
    step2_extract_pages()
    step3_filter_and_extract_bubbles()
