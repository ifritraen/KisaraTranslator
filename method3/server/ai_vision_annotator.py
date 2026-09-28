import os
import json
import base64
import argparse
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime

import cv2
import numpy as np

DATASET_ROOT = Path(__file__).resolve().parent.parent / "bubble_dataset"

def load_env_key() -> Optional[str]:
    # Check environment or .env
    key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if key:
        return key
    env_file = Path(__file__).resolve().parent.parent / ".env"
    if env_file.exists():
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("GEMINI_API_KEY="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
                if line.startswith("GOOGLE_API_KEY="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    return None

def process_with_advanced_geometry(img_path: Path) -> Optional[Dict[str, Any]]:
    """
    High-precision geometry pipeline with curvature extrema and polygon chord search.
    """
    img = cv2.imread(str(img_path))
    if img is None:
        return None
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    _, thresh = cv2.threshold(gray, 235, 255, cv2.THRESH_BINARY)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None

    bubble_cnt = max(contours, key=cv2.contourArea)
    approx = cv2.approxPolyDP(bubble_cnt, 2.0, True)
    if len(approx) < 5:
        return None

    hull = cv2.convexHull(approx, returnPoints=False)
    if hull is None or len(hull) <= 3:
        return None

    try:
        defects = cv2.convexityDefects(approx, hull)
    except Exception:
        return None

    if defects is None:
        return None

    candidates = []
    for i in range(defects.shape[0]):
        s, e, f, d = defects[i]
        depth = d / 256.0
        if depth > 15.0:
            pt = approx[f][0]
            candidates.append((depth, int(pt[0]), int(pt[1])))

    if len(candidates) < 2:
        return None

    candidates.sort(key=lambda c: c[0], reverse=True)

    best_pair = None
    best_score = -1.0
    for i in range(min(6, len(candidates))):
        d1, x1, y1 = candidates[i]
        for j in range(i + 1, min(8, len(candidates))):
            d2, x2, y2 = candidates[j]
            dist_sq = (x1 - x2)**2 + (y1 - y2)**2
            if dist_sq < 900:
                continue
            score = (d1 + d2) * np.sqrt(dist_sq)
            if score > best_score:
                best_score = score
                best_pair = ((x1, y1), (x2, y2))

    if not best_pair:
        p1 = (candidates[0][1], candidates[0][2])
        p2 = (candidates[1][1], candidates[1][2])
        best_pair = (p1, p2)

    (ax, ay), (bx, by) = best_pair
    box_size = 32

    return {
        "crunch_points": [
            {
                "id": 1,
                "label": "crunch_a",
                "bbox": [max(0, ax - box_size//2), max(0, ay - box_size//2), min(w, ax + box_size//2), min(h, ay + box_size//2)],
                "center": [ax, ay]
            },
            {
                "id": 2,
                "label": "crunch_b",
                "bbox": [max(0, bx - box_size//2), max(0, by - box_size//2), min(w, bx + box_size//2), min(h, by + box_size//2)],
                "center": [bx, by]
            }
        ],
        "dividing_lines": [
            {
                "line_id": 1,
                "points": [[ax, ay], [bx, by]],
                "cut_type": "straight"
            }
        ]
    }

def process_with_gemini_vision(img_path: Path, api_key: str) -> Optional[Dict[str, Any]]:
    """
    Multimodal AI Vision fallback using Gemini API.
    """
    import requests
    img = cv2.imread(str(img_path))
    if img is None:
        return None
    h, w = img.shape[:2]

    with open(img_path, "rb") as f:
        img_b64 = base64.b64encode(f.read()).decode("utf-8")

    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={api_key}"
    headers = {"Content-Type": "application/json"}
    prompt = f"""You are an expert manga typesetting and speech bubble annotator.
This image shows a conjoined manga speech bubble of dimensions {w}x{h}.
Identify the two opposite inward notch points (crunch points) at the narrow waist where the two lobes meet:
- Notch A: [x, y] of the left or upper inward concavity apex.
- Notch B: [x, y] of the right or lower inward concavity apex.
Respond strictly in valid JSON format:
{{
  "notch_a": [x, y],
  "notch_b": [x, y]
}}"""

    payload = {
        "contents": [{
            "parts": [
                {"text": prompt},
                {"inline_data": {"mime_type": "image/png", "data": img_b64}}
            ]
        }],
        "generationConfig": {"response_mime_type": "application/json"}
    }

    try:
        resp = requests.post(url, headers=headers, json=payload, timeout=25)
        if resp.status_code == 200:
            result = resp.json()
            text = result["candidates"][0]["content"]["parts"][0]["text"]
            data = json.loads(text)
            ax, ay = data["notch_a"]
            bx, by = data["notch_b"]
            box_size = 32
            return {
                "crunch_points": [
                    {"id": 1, "label": "crunch_a", "bbox": [max(0, ax - box_size//2), max(0, ay - box_size//2), min(w, ax + box_size//2), min(h, ay + box_size//2)], "center": [int(ax), int(ay)]},
                    {"id": 2, "label": "crunch_b", "bbox": [max(0, bx - box_size//2), max(0, by - box_size//2), min(w, bx + box_size//2), min(h, by + box_size//2)], "center": [int(bx), int(by)]}
                ],
                "dividing_lines": [{"line_id": 1, "points": [[int(ax), int(ay)], [int(bx), int(by)]], "cut_type": "straight"}]
            }
    except Exception as ex:
        print(f"Gemini API error: {ex}")
    return None

def process_conjoined_queue(batch: str = "batch_01"):
    batch_dir = DATASET_ROOT / batch
    conjoined_dir = batch_dir / "conjoined"
    unverified_dir = batch_dir / "unverified_annotated"
    unverified_dir.mkdir(parents=True, exist_ok=True)

    manifest_file = batch_dir / "manifest.json"
    with open(manifest_file, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    api_key = load_env_key()
    images = list(conjoined_dir.glob("*.png"))
    print(f"Found {len(images)} images in {conjoined_dir}")

    processed_count = 0
    for img_path in images:
        bubble_id = img_path.stem.split("_")[0] + "_" + img_path.stem.split("_")[1] + "_" + img_path.stem.split("_")[2]
        # Check if already in unverified
        target_json = unverified_dir / f"{bubble_id}.json"
        if target_json.exists():
            continue

        item = next((b for b in manifest if b["bubble_id"] == bubble_id), None)
        if not item:
            continue

        # Try API or Geometry
        annot_res = None
        if api_key:
            annot_res = process_with_gemini_vision(img_path, api_key)
        if not annot_res:
            annot_res = process_with_advanced_geometry(img_path)

        if annot_res:
            annot_data = {
                "bubble_id": bubble_id,
                "batch": batch,
                "filename": item["filename"],
                "page_name": item["page_name"],
                "page_dimensions": item["page_dimensions"],
                "crop_bbox": item["crop_bbox"],
                "crop_dimensions": item["crop_dimensions"],
                "is_conjoined": True,
                "crunch_points": annot_res["crunch_points"],
                "dividing_lines": annot_res["dividing_lines"],
                "notes": "auto_generated_for_verification",
                "timestamp": datetime.now().isoformat()
            }
            with open(target_json, "w", encoding="utf-8") as f:
                json.dump(annot_data, f, indent=2)

            item["status"] = "unverified"
            processed_count += 1
            print(f"Processed {bubble_id} -> unverified_annotated/")

    with open(manifest_file, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print(f"Finished. Total {processed_count} new annotations placed in unverified_annotated/")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch", default="batch_01")
    args = parser.parse_args()
    process_conjoined_queue(args.batch)
