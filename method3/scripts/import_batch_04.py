import os
import sys
import subprocess
import shutil
import zipfile
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

ADB_DEVICE = "192.168.0.181:5555"
BASE_DIR = Path("d:/C/KisaraTranslator/method3")
CBZ_DIR = BASE_DIR / "raw_manga_batch_04" / "cbz"
PAGES_ALL_DIR = BASE_DIR / "raw_manga_batch_04" / "extracted_pages"
PAGES_WITH_BUBBLES_DIR = BASE_DIR / "raw_manga_batch_04" / "pages_with_bubbles"
BATCH_OUTPUT = BASE_DIR / "bubble_dataset" / "batch_04"
MODEL_PATH = BASE_DIR / "models" / "manga109_segmentation_bubble_1024.onnx"

TARGET_BUBBLE_COUNT = 1000

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

def step1_pull_new_cbz():
    print("\n--- STEP 1: Pulling New Japanese CBZ files from Mobile Device ---")
    CBZ_DIR.mkdir(parents=True, exist_ok=True)
    
    # Load batch 3 metadata to exclude previous CBZ
    prev_paths = set()
    b3_meta_p = BASE_DIR / "raw_manga_batch_03" / "cbz" / "metadata.json"
    if b3_meta_p.exists():
        with open(b3_meta_p, encoding="utf-8") as f:
            b3_data = json.load(f)
            prev_paths.update(v["remote_path"] for v in b3_data.values())

    proc = subprocess.run(
        ["adb", "-s", ADB_DEVICE, "shell", "find /sdcard/Aaaaa/Otaku/Komikku/downloads -name '*.cbz'"],
        capture_output=True
    )
    raw_output = proc.stdout.decode("utf-8", errors="replace")
    all_remote = [line.strip() for line in raw_output.splitlines() if line.strip().endswith(".cbz")]
    
    # Filter to only new Japanese manga
    new_remote = [p for p in all_remote if "NHentai (JA)" in p and p not in prev_paths]
    print(f"Found {len(new_remote)} new Japanese CBZ files on mobile device.")
    
    metadata = {}
    pulled = 0
    for idx, remote_path in enumerate(new_remote, start=1):
        safe_id = f"cbz_{idx:02d}.cbz"
        local_path = CBZ_DIR / safe_id
        rel_info = remote_path.replace("/sdcard/Aaaaa/Otaku/Komikku/downloads/", "")
        metadata[safe_id] = {
            "remote_path": remote_path,
            "title_info": rel_info
        }
        
        if local_path.exists() and local_path.stat().st_size > 10000:
            print(f"[{idx}/{len(new_remote)}] Already downloaded: {safe_id}")
            pulled += 1
            continue
            
        print(f"[{idx}/{len(new_remote)}] Pulling {safe_id} ({rel_info[:40]}...)...")
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

def step3_extract_and_sample_1000():
    print("\n--- STEP 3: Detecting Bubbles and Slicing Pool for 1000-Bubble Test Batch ---")
    PAGES_WITH_BUBBLES_DIR.mkdir(parents=True, exist_ok=True)
    images_output = BATCH_OUTPUT / "images"
    non_annotated_output = BATCH_OUTPUT / "non_annotated"
    
    for sub in ["images", "non_annotated", "annotations", "verified_annotated", "conjoined", "unverified_annotated", "recheck_annotated", "discarded"]:
        (BATCH_OUTPUT / sub).mkdir(parents=True, exist_ok=True)
        
    print(f"Loading Manga109 ONNX segmenter: {MODEL_PATH}")
    segmenter = BubbleSegmenter(str(MODEL_PATH))
    
    valid_exts = {".jpg", ".jpeg", ".png", ".webp"}
    all_pages = sorted([p for p in PAGES_ALL_DIR.iterdir() if p.suffix.lower() in valid_exts])
    print(f"Scanning {len(all_pages)} total extracted pages...")
    
    bubble_candidates = []
    pages_with_bubbles_count = 0
    
    for idx, page_path in enumerate(all_pages, start=1):
        img = cv2.imread(str(page_path))
        if img is None:
            continue
            
        bubbles = segmenter.detect(img, conf_thresh=0.35)
        if len(bubbles) == 0:
            continue
            
        pages_with_bubbles_count += 1
        raw_page_dest = PAGES_WITH_BUBBLES_DIR / page_path.name
        if not raw_page_dest.exists():
            shutil.copy2(page_path, raw_page_dest)
            
        h, w = img.shape[:2]
        for b_idx, bubble in enumerate(bubbles, start=1):
            bubble_candidates.append({
                "page_path": page_path,
                "page_name": page_path.name,
                "page_index": pages_with_bubbles_count,
                "bubble_index_on_page": b_idx,
                "page_w": w,
                "page_h": h,
                "bubble": bubble
            })
            
        if pages_with_bubbles_count % 50 == 0 or idx == len(all_pages):
            print(f"[{idx}/{len(all_pages)}] Scanned: {page_path.name} -> {len(bubbles)} bubbles (Pool size: {len(bubble_candidates)})")
            
    print(f"\nTotal candidate bubbles discovered across all {len(all_pages)} pages: {len(bubble_candidates)}")
    
    # Seeded random sample of exactly 1000 bubbles across all manga books
    random.seed(42)
    sample_size = min(TARGET_BUBBLE_COUNT, len(bubble_candidates))
    selected_samples = random.sample(bubble_candidates, sample_size)
    
    # Sort selected samples by page order and reading position for natural annotation flow
    selected_samples.sort(key=lambda s: (s["page_index"], s["bubble_index_on_page"]))
    
    manifest = []
    print(f"Slicing and saving exactly {sample_size} bubbles into batch_04...")
    
    # Cache page images to avoid redundant disk reads
    current_page_p = None
    current_img = None
    
    for b_idx, item in enumerate(selected_samples, start=1):
        if item["page_path"] != current_page_p:
            current_page_p = item["page_path"]
            current_img = cv2.imread(str(current_page_p))
            
        bubble = item["bubble"]
        x1, y1, x2, y2 = bubble["raw_bbox"]
        crop_x1, crop_y1, crop_x2, crop_y2 = bubble["crop_bbox"]
        
        crop_img = current_img[crop_y1:crop_y2, crop_x1:crop_x2].copy()
        
        bubble_id = f"batch_04_b{b_idx:05d}"
        filename = f"{bubble_id}_{item['page_path'].stem}_b{item['bubble_index_on_page']:02d}.png"
        
        cv2.imwrite(str(images_output / filename), crop_img)
        cv2.imwrite(str(non_annotated_output / filename), crop_img)
        
        manifest.append({
            "bubble_id": bubble_id,
            "batch": "batch_04",
            "filename": filename,
            "page_name": item["page_name"],
            "page_index": item["page_index"],
            "bubble_index_on_page": item["bubble_index_on_page"],
            "page_dimensions": {"width": item["page_w"], "height": item["page_h"]},
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
    print(f"BATCH 4 PREPARATION COMPLETE:")
    print(f"  Total Bubbles in Batch 4:    {len(manifest)}")
    print(f"  Location:                    {BATCH_OUTPUT}")
    print(f"  Manifest Path:               {manifest_path}")
    print("="*60)

if __name__ == "__main__":
    step1_pull_new_cbz()
    step2_extract_pages()
    step3_extract_and_sample_1000()
