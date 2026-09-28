import os
import sys
import json
import argparse
from pathlib import Path
from typing import List, Dict, Any

import cv2
import numpy as np
import onnxruntime as ort

def compute_adaptive_padding(bw: int, bh: int) -> int:
    """
    Computes natural adaptive padding between 12px and 16px based on bubble size.
    Small bubbles (<=150px) get 12px padding; large bubbles (>=500px) get 16px padding.
    """
    dim = max(bw, bh)
    pad = int(np.clip(round(12 + (dim - 150) * (4.0 / 350.0)), 12, 16))
    return pad

class BubbleSegmenter:
    """
    YOLO11-seg inference engine for manga109 speech balloon segmentation.
    Produces pixel-accurate bubble instance masks and bounding boxes.
    """
    def __init__(self, model_path: str):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(model_path, opts)
        self.input_name = self.session.get_inputs()[0].name
        self.input_size = 1024

    def detect(self, image: np.ndarray, conf_thresh: float = 0.35, iou_thresh: float = 0.45) -> List[Dict[str, Any]]:
        orig_h, orig_w = image.shape[:2]
        
        # 1. Preprocess: resize to 1024x1024, normalize to [0, 1] RGB NCHW
        resized = cv2.resize(image, (self.input_size, self.input_size))
        blob = cv2.dnn.blobFromImage(resized, 1.0 / 255.0, (self.input_size, self.input_size), swapRB=True)
        
        # 2. Run inference
        outputs = self.session.run(None, {self.input_name: blob})
        o0 = outputs[0][0]  # shape: (37, 21504) -> (cx, cy, w, h, conf, 32 coeffs)
        protos = outputs[1][0]  # shape: (32, 256, 256)
        
        # 3. Filter candidate anchors
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
                
        # 4. Greedy NMS
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
                
        # 5. Decode prototype masks for kept candidates with 12-16px adaptive padding
        proto_flat = protos.reshape(32, -1)  # (32, 256*256)
        results = []
        
        for cand in kept_candidates:
            x1, y1, x2, y2, conf, idx = cand
            pad = compute_adaptive_padding(x2 - x1, y2 - y1)
            crop_x1 = max(0, x1 - pad)
            crop_y1 = max(0, y1 - pad)
            crop_x2 = min(orig_w, x2 + pad)
            crop_y2 = min(orig_h, y2 + pad)
            
            # Linear combination of mask coefficients with prototypes
            coeffs = o0[5:37, idx]  # (32,)
            mask_256 = (coeffs @ proto_flat).reshape(256, 256)
            mask_sig = 1.0 / (1.0 + np.exp(-mask_256))
            
            # Resize prototype mask to full page dimensions, then crop to bounding box
            mask_page = cv2.resize(mask_sig, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
            mask_crop = (mask_page[crop_y1:crop_y2, crop_x1:crop_x2] >= 0.40).astype(np.uint8)
            
            # Slight dilation (3x3) to guarantee bubble border strokes are 100% preserved
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
            safe_mask = cv2.dilate(mask_crop, kernel, iterations=1)
            
            area = int(np.sum(mask_crop))
            if area >= 150:
                results.append({
                    "raw_bbox": (x1, y1, x2, y2),
                    "crop_bbox": (crop_x1, crop_y1, crop_x2, crop_y2),
                    "padding": pad,
                    "conf": conf,
                    "area": area,
                    "mask": safe_mask
                })
                
        # Sort in reading order: top-to-bottom, right-to-left (Japanese manga RTL)
        results.sort(key=lambda b: (b["raw_bbox"][1] // 150, -b["raw_bbox"][0]))
        return results

def main():
    parser = argparse.ArgumentParser(description="Extract speech bubble crops using Manga109 YOLO11-seg")
    parser.add_argument("--input_dir", type=str, 
                        default=r"c:\Users\akhla\Documents\antigravity\serene-borg\.aaa\crunch_pc_sim\data\manga_database\raw_pages",
                        help="Path to directory containing raw manga page images")
    parser.add_argument("--output_dir", type=str, 
                        default=r"d:\C\KisaraTranslator\method3\bubble_dataset",
                        help="Root output directory for bubble dataset batches")
    parser.add_argument("--batch_name", type=str, default="batch_01",
                        help="Batch identifier (e.g. batch_01, batch_02)")
    parser.add_argument("--model_path", type=str, 
                        default=r"d:\C\KisaraTranslator\method3\models\manga109_segmentation_bubble_1024.onnx",
                        help="Path to manga109 ONNX model")
    parser.add_argument("--limit_pages", type=int, default=0,
                        help="Number of pages to process (0 = all pages)")
    parser.add_argument("--conf_thresh", type=float, default=0.35,
                        help="Confidence threshold for bubble detection")
    args = parser.parse_args()

    input_path = Path(args.input_dir)
    batch_output = Path(args.output_dir) / args.batch_name
    images_output = batch_output / "images"
    non_annotated_output = batch_output / "non_annotated"
    
    # Ensure fresh batch output directories and reset annotations
    for folder_name in ["images", "non_annotated", "annotations", "verified_annotated", "conjoined", "unverified_annotated", "recheck_annotated", "discarded"]:
        folder = batch_output / folder_name
        if folder.exists():
            for old_file in folder.glob("*.*"):
                try:
                    old_file.unlink()
                except Exception:
                    pass
        folder.mkdir(parents=True, exist_ok=True)
    
    print(f"Loading Manga109 model from: {args.model_path}")
    segmenter = BubbleSegmenter(args.model_path)

    # Collect valid page images
    valid_exts = {".jpg", ".jpeg", ".png", ".webp"}
    all_files = sorted([f for f in input_path.iterdir() if f.suffix.lower() in valid_exts])
    
    if args.limit_pages > 0 and len(all_files) > args.limit_pages:
        selected_files = all_files[:args.limit_pages]
    else:
        selected_files = all_files

    print(f"\n============================================================")
    print(f"Starting {args.batch_name} Extraction:")
    print(f"  Input Pages:    {len(selected_files)} images from {input_path}")
    print(f"  Batch Output:   {batch_output}")
    print(f"  Crop Padding:   12-16px Adaptive Natural Pixels")
    print(f"============================================================\n")

    manifest = []
    total_bubbles = 0

    for p_idx, page_file in enumerate(selected_files, start=1):
        img = cv2.imread(str(page_file))
        if img is None:
            print(f"[{p_idx}/{len(selected_files)}] Failed to load: {page_file.name}")
            continue

        h, w = img.shape[:2]
        bubbles = segmenter.detect(img, conf_thresh=args.conf_thresh)

        if p_idx % 25 == 0 or p_idx == len(selected_files) or (len(bubbles) > 0 and p_idx <= 10):
            print(f"[{p_idx}/{len(selected_files)}] {page_file.name} ({w}x{h}) -> {len(bubbles)} bubbles (Total: {total_bubbles + len(bubbles)})")

        for b_idx, bubble in enumerate(bubbles, start=1):
            total_bubbles += 1
            x1, y1, x2, y2 = bubble["raw_bbox"]
            crop_x1, crop_y1, crop_x2, crop_y2 = bubble["crop_bbox"]

            # Pure raw crop (100% natural pixels, no destructive white masking)
            crop_img = img[crop_y1:crop_y2, crop_x1:crop_x2].copy()

            bubble_id = f"{args.batch_name}_b{total_bubbles:05d}"
            filename = f"{bubble_id}_{page_file.stem}_p{p_idx:03d}_{b_idx:02d}.png"
            
            # Save to both images/ and non_annotated/
            cv2.imwrite(str(images_output / filename), crop_img)
            cv2.imwrite(str(non_annotated_output / filename), crop_img)

            # Record metadata in manifest
            manifest.append({
                "bubble_id": bubble_id,
                "batch": args.batch_name,
                "filename": filename,
                "page_name": page_file.name,
                "page_index": p_idx,
                "bubble_index_on_page": b_idx,
                "page_dimensions": {"width": w, "height": h},
                "raw_bbox": [x1, y1, x2, y2],
                "crop_bbox": [crop_x1, crop_y1, crop_x2, crop_y2],
                "crop_dimensions": {"width": int(crop_x2 - crop_x1), "height": int(crop_y2 - crop_y1)},
                "padding_used": bubble["padding"],
                "confidence": round(float(bubble["conf"]), 4),
                "mask_area_px": int(bubble["area"]),
                "is_conjoined": None,       # To be labeled in App
                "is_discarded": False,      # Flag to discard bad crops / noise
                "discard_reason": None,     # Optional reason (e.g. 'bad_crop', 'not_bubble', 'chopped')
                "status": "unlabeled"
            })

    # Save batch manifest.json
    manifest_path = batch_output / "manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print("\n" + "="*60)
    print(f"BATCH EXTRACTION COMPLETE: {args.batch_name}")
    print(f"Total Pages Processed:  {len(selected_files)}")
    print(f"Total Bubbles Extracted:{total_bubbles}")
    print(f"Batch Folder:           {batch_output}")
    print(f"Manifest JSON:          {manifest_path}")
    print("="*60)

if __name__ == "__main__":
    main()
