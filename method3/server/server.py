import os
import json
import shutil
import socket
from pathlib import Path
from typing import List, Optional, Dict, Any
from datetime import datetime

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
import uvicorn

app = FastAPI(title="Method 3 Bubble Annotation Sync Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    )

DATASET_ROOT = Path(__file__).resolve().parent.parent / "bubble_dataset"

# In-memory history for 1-click Undo
action_history: List[Dict[str, Any]] = []

def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def ensure_batch_dirs(batch: str):
    batch_dir = DATASET_ROOT / batch
    for d in ["raw", "non_annotated", "conjoined", "unverified_annotated", "verified_annotated", "recheck_annotated", "discarded"]:
        (batch_dir / d).mkdir(parents=True, exist_ok=True)

def load_manifest(batch: str) -> List[Dict[str, Any]]:
    manifest_file = DATASET_ROOT / batch / "manifest.json"
    if not manifest_file.exists():
        raise HTTPException(status_code=404, detail=f"Batch {batch} manifest not found")
    with open(manifest_file, "r", encoding="utf-8") as f:
        return json.load(f)

def save_manifest(batch: str, manifest: List[Dict[str, Any]]):
    manifest_file = DATASET_ROOT / batch / "manifest.json"
    with open(manifest_file, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

def find_image_file(batch: str, filename: str) -> Optional[Path]:
    batch_dir = DATASET_ROOT / batch
    # Check non_annotated, conjoined, images, discarded
    for sub in ["non_annotated", "conjoined", "images", "discarded"]:
        p = batch_dir / sub / filename
        if p.exists():
            return p
    return None

def auto_suggest_crunch_points(img_path: Path) -> Optional[Dict[str, Any]]:
    """
    Sub-pixel OpenCV Convexity Defect detector for conjoined manga speech bubbles.
    Runs in ~8ms without external dependencies or LLM tokens.
    """
    img = cv2.imread(str(img_path))
    if img is None:
        return None
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # Adaptive threshold based on interior bubble brightness (handles dark/aged scans and screentones)
    p95 = np.percentile(gray, 95)
    t = int(min(225, max(140, p95 * 0.90)))
    _, thresh = cv2.threshold(gray, t, 255, cv2.THRESH_BINARY)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None

    bubble_cnt = max(contours, key=cv2.contourArea)
    if cv2.contourArea(bubble_cnt) < 1500:
        return None

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

    min_depth = max(5.0, min(w, h) * 0.025)
    candidates = []
    for i in range(defects.shape[0]):
        s, e, f, d = defects[i]
        depth = d / 256.0
        if depth >= min_depth:
            pt = approx[f][0]
            candidates.append((depth, int(pt[0]), int(pt[1])))

    if not candidates:
        return None

    candidates.sort(key=lambda c: c[0], reverse=True)

    bx, by, bw, bh = cv2.boundingRect(bubble_cnt)
    text_mask = (gray < 90).astype(np.uint8)

    def line_text_cost(p1, p2):
        steps = max(int(np.hypot(p1[0] - p2[0], p1[1] - p2[1])), 1)
        dark = 0
        for s in range(1, steps):
            t = s / float(steps)
            lx = int(round(p1[0] * (1 - t) + p2[0] * t))
            ly = int(round(p1[1] * (1 - t) + p2[1] * t))
            if cv2.pointPolygonTest(bubble_cnt, (lx, ly), False) < 0:
                return 999999
            if 0 <= ly < h and 0 <= lx < w and text_mask[ly, lx]:
                dark += 1
        return dark

    best_pair = None
    best_score = -1.0

    # Try candidate pairs
    for i in range(len(candidates)):
        d1, x1, y1 = candidates[i]
        for j in range(i + 1, len(candidates)):
            d2, x2, y2 = candidates[j]
            dist = np.hypot(x1 - x2, y1 - y2)
            if dist < 20 or dist > max(w, h) * 0.95:
                continue

            dx = abs(x1 - x2)
            dy = abs(y1 - y2)

            # Aspect ratio orientation alignment:
            # If vertically stacked (bh > bw * 1.15), waist cut line must be roughly horizontal (dy <= dx * 0.8)
            # If horizontally stacked (bw > bh * 1.15), waist cut line must be roughly vertical (dx <= dy * 0.8)
            if bh > bw * 1.15:
                if dy > dx * 0.8:
                    continue
                alignment = 1.0 / (1.0 + (dy / (dx + 1e-4)))
            elif bw > bh * 1.15:
                if dx > dy * 0.8:
                    continue
                alignment = 1.0 / (1.0 + (dx / (dy + 1e-4)))
            else:
                alignment = 1.0

            mid = ((x1 + x2) / 2.0, (y1 + y2) / 2.0)
            in_poly = cv2.pointPolygonTest(bubble_cnt, mid, False)
            if in_poly < 0:
                continue

            dark = line_text_cost((x1, y1), (x2, y2))
            if dark > max(w, h) * 0.15:
                continue

            score = (d1 + d2) * alignment - dark * 1.5

            if score > best_score:
                best_score = score
                best_pair = ((x1, y1), (x2, y2))

    # Single deep notch fallback (cast ray across waist to opposite boundary)
    if not best_pair and candidates:
        for d1, x1, y1 in candidates:
            if d1 < 8.0:
                continue
            best_opp = None
            min_cost = 999999
            for pt in bubble_cnt[::4]:
                px, py = int(pt[0][0]), int(pt[0][1])
                dist = np.hypot(x1 - px, y1 - py)
                if dist < 25 or dist > max(w, h) * 0.90:
                    continue

                dx = abs(x1 - px)
                dy = abs(y1 - py)
                if bh > bw * 1.15 and dy > dx * 0.8:
                    continue
                if bw > bh * 1.15 and dx > dy * 0.8:
                    continue

                dark = line_text_cost((x1, y1), (px, py))
                if dark >= 999999:
                    continue

                cost = dark * 10.0 + min(dx, dy) * 0.8 + dist * 0.1
                if cost < min_cost:
                    min_cost = cost
                    best_opp = (px, py)
            if best_opp:
                best_pair = ((x1, y1), best_opp)
                break

    if not best_pair:
        if candidates:
            return {
                "success": False,
                "candidate_points": [[x, y] for _, x, y in candidates],
                "message": "No aligned notch pair found"
            }
        return None

    (ax, ay), (bx, by) = best_pair
    box_size = 32

    return {
        "success": True,
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
        ],
        "candidate_points": [[x, y] for _, x, y in candidates]
    }

class CrunchPoint(BaseModel):
    id: int
    label: str
    bbox: List[int]  # [x1, y1, x2, y2]
    center: List[int]  # [cx, cy]

class DividingLine(BaseModel):
    line_id: int
    points: List[List[int]]  # [[x1, y1], [x2, y2], ...]
    cut_type: Optional[str] = "straight"

class AnnotateRequest(BaseModel):
    batch: str
    bubble_id: str
    is_conjoined: bool
    crunch_points: Optional[List[CrunchPoint]] = []
    dividing_lines: Optional[List[DividingLine]] = []
    notes: Optional[str] = ""

class AutoSuggestRequest(BaseModel):
    batch: str
    bubble_id: str

class FlagConjoinedRequest(BaseModel):
    batch: str
    bubble_id: str
    reason: Optional[str] = "auto_suggest_rejected"

class VerifyRequest(BaseModel):
    batch: str
    bubble_id: str
    verified: bool  # True -> verified_annotated, False -> recheck_annotated
    crunch_points: Optional[List[CrunchPoint]] = []
    dividing_lines: Optional[List[DividingLine]] = []
    notes: Optional[str] = ""

class DiscardRequest(BaseModel):
    batch: str
    bubble_id: str
    reason: str = "bad_crop"

@app.get("/api/status")
def get_status(batch: str = "batch_01"):
    batches = [d.name for d in DATASET_ROOT.iterdir() if d.is_dir() and (d / "manifest.json").exists()]
    if not batches:
        return {"status": "no_batches_found", "local_ip": get_local_ip()}

    active_batch = batch if batch in batches else batches[0]
    ensure_batch_dirs(active_batch)
    manifest = load_manifest(active_batch)

    total = len(manifest)
    discarded = sum(1 for b in manifest if b.get("is_discarded", False))
    
    # Counts across statuses
    unlabeled = sum(1 for b in manifest if b.get("status") == "unlabeled" and not b.get("is_discarded", False))
    verified = sum(1 for b in manifest if b.get("status") in ["verified", "annotated"] and not b.get("is_discarded", False))
    conjoined_queued = sum(1 for b in manifest if b.get("status") == "conjoined" and not b.get("is_discarded", False))
    unverified = sum(1 for b in manifest if b.get("status") == "unverified" and not b.get("is_discarded", False))
    recheck = sum(1 for b in manifest if b.get("status") == "recheck" and not b.get("is_discarded", False))
    
    conjoined_total = sum(1 for b in manifest if b.get("is_conjoined") is True and not b.get("is_discarded", False))
    single = sum(1 for b in manifest if b.get("is_conjoined") is False and not b.get("is_discarded", False))
    
    # Compute totals across all batches
    total_all = 0
    unlabeled_all = 0
    annotated_all = 0
    for b_name in batches:
        if b_name == active_batch:
            total_all += total
            unlabeled_all += unlabeled
            annotated_all += (verified + discarded)
        else:
            b_man = load_manifest(b_name)
            b_tot = len(b_man)
            b_unlab = sum(1 for x in b_man if x.get("status") == "unlabeled" and not x.get("is_discarded", False))
            total_all += b_tot
            unlabeled_all += b_unlab
            annotated_all += (b_tot - b_unlab)

    return {
        "status": "ok",
        "local_ip": get_local_ip(),
        "batches": batches,
        "current_batch": active_batch,
        "total": total,
        "unlabeled": unlabeled,
        "annotated": verified,
        "verified": verified,
        "conjoined_queued": conjoined_queued,
        "unverified": unverified,
        "recheck": recheck,
        "conjoined": conjoined_total,
        "single": single,
        "discarded": discarded,
        "percent_complete": round((verified + discarded) / total * 100, 1) if total > 0 else 0,
        "batch_done": verified + discarded,
        "batch_left": unlabeled,
        "total_all": total_all,
        "done_all": annotated_all,
        "left_all": unlabeled_all
    }

@app.get("/api/batches")
def list_batches():
    batches = [d.name for d in DATASET_ROOT.iterdir() if d.is_dir() and (d / "manifest.json").exists()]
    return {"batches": batches}

@app.get("/api/bubble/next")
def get_next_bubble(batch: str = "batch_01"):
    ensure_batch_dirs(batch)
    manifest = load_manifest(batch)

    # Find first bubble that is unlabeled and not discarded
    for idx, item in enumerate(manifest):
        if item.get("status") == "unlabeled" and not item.get("is_discarded", False):
            return {
                "bubble": item,
                "image_url": f"/api/image/{batch}/{item['filename']}",
                "index": idx + 1,
                "total": len(manifest)
            }

    return {
        "bubble": None,
        "message": "All bubbles in this batch have been annotated or discarded!"
    }

@app.get("/api/bubble/verified/list")
def list_verified_bubbles(
    batch: str = "batch_01",
    conjoined_only: bool = False,
    singles_only: bool = False,
    filter: Optional[str] = None
):
    manifest = load_manifest(batch)
    items = []
    for idx, item in enumerate(manifest):
        if item.get("status") in ["verified", "annotated"] and not item.get("is_discarded", False):
            is_conj = bool(item.get("is_conjoined", False))
            if singles_only or filter in ["single", "singles"]:
                if is_conj:
                    continue
            elif conjoined_only or filter in ["conjoined"]:
                if not is_conj:
                    continue
            items.append({
                "bubble_id": item["bubble_id"],
                "filename": item["filename"],
                "is_conjoined": is_conj,
                "manifest_index": idx + 1
            })
    return {"batch": batch, "total": len(items), "items": items}

@app.get("/api/bubble/verified/{batch}/{bubble_id}")
def get_verified_bubble(batch: str, bubble_id: str):
    manifest = load_manifest(batch)
    found_idx = -1
    for idx, item in enumerate(manifest):
        if item["bubble_id"] == bubble_id:
            found_idx = idx
            break

    if found_idx == -1:
        raise HTTPException(status_code=404, detail="Bubble not found in manifest")

    item = manifest[found_idx]
    annot_file = DATASET_ROOT / batch / "verified_annotated" / f"{bubble_id}.json"
    crunch_points = []
    dividing_lines = []
    if annot_file.exists():
        try:
            with open(annot_file, "r", encoding="utf-8") as f:
                adata = json.load(f)
                crunch_points = adata.get("crunch_points", [])
                dividing_lines = adata.get("dividing_lines", [])
        except Exception:
            pass

    return {
        "bubble": item,
        "image_url": f"/api/image/{batch}/{item['filename']}",
        "is_conjoined": item.get("is_conjoined", False),
        "crunch_points": crunch_points,
        "dividing_lines": dividing_lines,
        "index": found_idx + 1,
        "total": len(manifest)
    }

@app.get("/api/bubble/{batch}/{bubble_id}")
def get_bubble(batch: str, bubble_id: str):
    manifest = load_manifest(batch)
    for idx, item in enumerate(manifest):
        if item.get("bubble_id") == bubble_id:
            return {
                "bubble": item,
                "image_url": f"/api/image/{batch}/{item['filename']}",
                "index": idx + 1,
                "total": len(manifest)
            }
    raise HTTPException(status_code=404, detail="Bubble not found")

@app.get("/api/image/{batch}/{filename}")
def get_image(batch: str, filename: str):
    img_path = find_image_file(batch, filename)
    if not img_path or not img_path.exists():
        raise HTTPException(status_code=404, detail="Image not found")
    return FileResponse(img_path, media_type="image/png")

@app.post("/api/bubble/auto_suggest")
def auto_suggest(req: AutoSuggestRequest):
    manifest = load_manifest(req.batch)
    item = next((b for b in manifest if b["bubble_id"] == req.bubble_id), None)
    if not item:
        raise HTTPException(status_code=404, detail="Bubble not found")

    img_path = find_image_file(req.batch, item["filename"])
    if not img_path:
        raise HTTPException(status_code=404, detail="Image file not found")

    res = auto_suggest_crunch_points(img_path)
    if res:
        return res
    return {"success": False, "message": "No obvious concavity defects detected"}

@app.post("/api/bubble/flag_conjoined")
def flag_conjoined(req: FlagConjoinedRequest):
    """
    When auto-suggest is rejected by user, copy crop to conjoined/ folder
    so it can be processed by the Multimodal AI Vision engine.
    """
    ensure_batch_dirs(req.batch)
    manifest = load_manifest(req.batch)
    found_idx = -1
    for idx, item in enumerate(manifest):
        if item["bubble_id"] == req.bubble_id:
            found_idx = idx
            break

    if found_idx == -1:
        raise HTTPException(status_code=404, detail="Bubble not found in manifest")

    filename = manifest[found_idx]["filename"]
    src_img = find_image_file(req.batch, filename)
    dest_img = DATASET_ROOT / req.batch / "conjoined" / filename

    if src_img and src_img.exists() and not dest_img.exists():
        shutil.copy2(src_img, dest_img)

    # Backup for undo
    action_history.append({
        "type": "flag_conjoined",
        "batch": req.batch,
        "bubble_id": req.bubble_id,
        "prev_state": dict(manifest[found_idx]),
        "copied_file": str(dest_img) if dest_img.exists() else None
    })

    manifest[found_idx]["is_conjoined"] = True
    manifest[found_idx]["status"] = "conjoined"
    manifest[found_idx]["conjoined_at"] = datetime.now().isoformat()
    manifest[found_idx]["flag_reason"] = req.reason
    save_manifest(req.batch, manifest)

    return {"success": True, "bubble_id": req.bubble_id, "status": "conjoined"}

@app.post("/api/annotate")
def save_annotation(req: AnnotateRequest):
    """
    Saves verified annotation to verified_annotated/ directory.
    """
    ensure_batch_dirs(req.batch)
    manifest = load_manifest(req.batch)
    found_idx = -1
    for idx, item in enumerate(manifest):
        if item["bubble_id"] == req.bubble_id:
            found_idx = idx
            break

    if found_idx == -1:
        raise HTTPException(status_code=404, detail="Bubble not found in manifest")

    verified_dir = DATASET_ROOT / req.batch / "verified_annotated"
    annot_dir = DATASET_ROOT / req.batch / "annotations"

    annot_data = {
        "bubble_id": req.bubble_id,
        "batch": req.batch,
        "filename": manifest[found_idx]["filename"],
        "page_name": manifest[found_idx]["page_name"],
        "page_dimensions": manifest[found_idx]["page_dimensions"],
        "crop_bbox": manifest[found_idx]["crop_bbox"],
        "crop_dimensions": manifest[found_idx]["crop_dimensions"],
        "is_conjoined": req.is_conjoined,
        "crunch_points": [p.dict() for p in req.crunch_points] if req.crunch_points else [],
        "dividing_lines": [l.dict() for l in req.dividing_lines] if req.dividing_lines else [],
        "notes": req.notes,
        "timestamp": datetime.now().isoformat()
    }

    # Save to verified_annotated/
    verified_file = verified_dir / f"{req.bubble_id}.json"
    with open(verified_file, "w", encoding="utf-8") as f:
        json.dump(annot_data, f, indent=2)

    # Maintain annotations/ backward-compatibility
    compat_file = annot_dir / f"{req.bubble_id}.json"
    with open(compat_file, "w", encoding="utf-8") as f:
        json.dump(annot_data, f, indent=2)

    # Backup previous state for Undo
    action_history.append({
        "type": "annotate",
        "batch": req.batch,
        "bubble_id": req.bubble_id,
        "prev_state": dict(manifest[found_idx]),
        "annot_file": str(verified_file)
    })

    # If conjoined, ensure the image is also present in conjoined/ directory
    if req.is_conjoined:
        dest_img = DATASET_ROOT / req.batch / "conjoined" / manifest[found_idx]["filename"]
        src_img = find_image_file(req.batch, manifest[found_idx]["filename"])
        if src_img and src_img.exists() and not dest_img.exists():
            try:
                shutil.copy2(src_img, dest_img)
            except Exception as e:
                print(f"Warning: Failed to copy {src_img} to conjoined: {e}")

    manifest[found_idx]["is_conjoined"] = req.is_conjoined
    manifest[found_idx]["status"] = "verified"
    manifest[found_idx]["annotated_at"] = annot_data["timestamp"]
    save_manifest(req.batch, manifest)

    return {"success": True, "bubble_id": req.bubble_id, "saved_file": str(verified_file)}

@app.get("/api/unverified/next")
def get_next_unverified(batch: str = "batch_01"):
    """
    Returns next unverified annotation (produced by AI Vision or auto pipeline)
    for mobile human review.
    """
    ensure_batch_dirs(batch)
    unverified_dir = DATASET_ROOT / batch / "unverified_annotated"
    files = sorted(list(unverified_dir.glob("*.json")))
    if not files:
        return {"item": None, "message": "No unverified annotations pending"}

    first_file = files[0]
    with open(first_file, "r", encoding="utf-8") as f:
        annot_data = json.load(f)

    filename = annot_data.get("filename", "")
    return {
        "item": {
            "bubble_id": annot_data["bubble_id"],
            "batch": batch,
            "filename": filename,
            "image_url": f"/api/image/{batch}/{filename}",
            "annotation": annot_data,
            "pending_count": len(files)
        }
    }

@app.post("/api/unverified/verify")
def verify_annotation(req: VerifyRequest):
    """
    Human confirms or rejects an AI-annotated bubble:
    - verified == True: moves to verified_annotated/
    - verified == False: moves to recheck_annotated/
    """
    ensure_batch_dirs(req.batch)
    unverified_file = DATASET_ROOT / req.batch / "unverified_annotated" / f"{req.bubble_id}.json"
    manifest = load_manifest(req.batch)

    found_idx = next((i for i, b in enumerate(manifest) if b["bubble_id"] == req.bubble_id), -1)

    annot_data = {}
    if unverified_file.exists():
        with open(unverified_file, "r", encoding="utf-8") as f:
            annot_data = json.load(f)

    # Apply adjustments if provided
    if req.crunch_points:
        annot_data["crunch_points"] = [p.dict() for p in req.crunch_points]
    if req.dividing_lines:
        annot_data["dividing_lines"] = [l.dict() for l in req.dividing_lines]
    if req.notes:
        annot_data["notes"] = req.notes
    annot_data["verified_at"] = datetime.now().isoformat()

    if req.verified:
        target_file = DATASET_ROOT / req.batch / "verified_annotated" / f"{req.bubble_id}.json"
        status = "verified"
    else:
        target_file = DATASET_ROOT / req.batch / "recheck_annotated" / f"{req.bubble_id}.json"
        status = "recheck"

    with open(target_file, "w", encoding="utf-8") as f:
        json.dump(annot_data, f, indent=2)

    # Remove from unverified
    if unverified_file.exists():
        unverified_file.unlink()

    if found_idx != -1:
        manifest[found_idx]["status"] = status
        save_manifest(req.batch, manifest)

    return {"success": True, "bubble_id": req.bubble_id, "status": status}

@app.post("/api/discard")
def discard_bubble(req: DiscardRequest):
    ensure_batch_dirs(req.batch)
    manifest = load_manifest(req.batch)
    found_idx = next((i for i, b in enumerate(manifest) if b["bubble_id"] == req.bubble_id), -1)
    if found_idx == -1:
        raise HTTPException(status_code=404, detail="Bubble not found in manifest")

    filename = manifest[found_idx]["filename"]
    src_img = find_image_file(req.batch, filename)
    discarded_dir = DATASET_ROOT / req.batch / "discarded"
    discarded_file = discarded_dir / filename
    moved_from = None
    if src_img and src_img.exists() and src_img != discarded_file:
        try:
            shutil.move(str(src_img), str(discarded_file))
            moved_from = str(src_img)
        except Exception as e:
            print(f"Warning: Failed to move discarded image {src_img}: {e}")

    action_history.append({
        "type": "discard",
        "batch": req.batch,
        "bubble_id": req.bubble_id,
        "prev_state": dict(manifest[found_idx]),
        "moved_from": moved_from,
        "discarded_file": str(discarded_file) if discarded_file.exists() else None
    })

    manifest[found_idx]["is_discarded"] = True
    manifest[found_idx]["discard_reason"] = req.reason
    manifest[found_idx]["status"] = "discarded"
    manifest[found_idx]["discarded_at"] = datetime.now().isoformat()
    save_manifest(req.batch, manifest)

    return {"success": True, "bubble_id": req.bubble_id, "reason": req.reason}

@app.post("/api/undo")
def undo_last_action():
    if not action_history:
        return {"success": False, "message": "No actions to undo"}

    last_action = action_history.pop()
    batch = last_action["batch"]
    bubble_id = last_action["bubble_id"]
    prev_state = last_action["prev_state"]

    manifest = load_manifest(batch)
    for idx, item in enumerate(manifest):
        if item["bubble_id"] == bubble_id:
            manifest[idx] = prev_state
            break
    save_manifest(batch, manifest)

    if last_action.get("annot_file") and os.path.exists(last_action["annot_file"]):
        try:
            os.remove(last_action["annot_file"])
        except Exception:
            pass

    if last_action.get("copied_file") and os.path.exists(last_action["copied_file"]):
        try:
            os.remove(last_action["copied_file"])
        except Exception:
            pass

    if last_action.get("discarded_file") and last_action.get("moved_from"):
        disc = last_action["discarded_file"]
        dest = last_action["moved_from"]
        if os.path.exists(disc):
            try:
                shutil.move(disc, dest)
            except Exception as e:
                print(f"Warning: Failed to restore discarded file {disc} -> {dest}: {e}")

    return {"success": True, "reverted_bubble_id": bubble_id}

if __name__ == "__main__":
    local_ip = get_local_ip()
    port = 8000
    print("\n" + "="*60)
    print(">> METHOD 3 ANNOTATION SYNC SERVER (DUAL LOOP) STARTED")
    print(f"Local URL:   http://localhost:{port}")
    print(f"Mobile URL:  http://{local_ip}:{port}")
    print("="*60 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=port)
