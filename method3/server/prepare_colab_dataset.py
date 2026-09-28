import os
import json
import shutil
import random
import zipfile
from pathlib import Path
import cv2
import numpy as np

random.seed(42)
np.random.seed(42)

DATASET_BASE = Path("d:/C/KisaraTranslator/method3/bubble_dataset")
BATCH_NAMES = ["batch_01", "batch_02", "batch_03"]
EXPORT_DIR = Path("d:/C/KisaraTranslator/method3/colab_export")
YOLO_DIR = EXPORT_DIR / "bubble_dataset"

def canonical_sort_kpts(kpts):
    """
    Enforces a strict mathematical total order:
    Keypoint 0 is ALWAYS the left-most notch (smaller x).
    If tied on x, Keypoint 0 is the top-most notch (smaller y).
    Keypoint 1 is ALWAYS the right-most (or bottom-most) notch.
    This guarantees 100.0% mathematical consistency with zero edge cases.
    """
    if len(kpts) < 2:
        return kpts
    return sorted(kpts, key=lambda p: (float(p[0]), float(p[1])))

def rotate_image_and_keypoints(image, kpts, angle_deg):
    """
    Rotates image around center and transforms keypoint coordinates, then re-enforces canonical sorting.
    """
    h, w = image.shape[:2]
    center = (w / 2.0, h / 2.0)
    rot_mat = cv2.getRotationMatrix2D(center, angle_deg, 1.0)
    
    cos_val = np.abs(rot_mat[0, 0])
    sin_val = np.abs(rot_mat[0, 1])
    new_w = int((h * sin_val) + (w * cos_val))
    new_h = int((h * cos_val) + (w * sin_val))
    
    rot_mat[0, 2] += (new_w / 2.0) - center[0]
    rot_mat[1, 2] += (new_h / 2.0) - center[1]
    
    rotated_img = cv2.warpAffine(image, rot_mat, (new_w, new_h), borderMode=cv2.BORDER_REPLICATE)
    
    transformed_kpts = []
    for (kx, ky) in kpts:
        orig_pt = np.array([kx, ky, 1.0])
        new_pt = rot_mat.dot(orig_pt)
        transformed_kpts.append((float(new_pt[0]), float(new_pt[1])))
        
    canon_kpts = canonical_sort_kpts(transformed_kpts)
    return rotated_img, canon_kpts, new_w, new_h

def flip_image_and_keypoints(image, kpts):
    """
    Horizontally flips image, mirrors keypoint x-coordinates, and re-sorts canonically.
    """
    h, w = image.shape[:2]
    flipped_img = cv2.flip(image, 1)
    raw_flipped = [(float(w - 1 - kx), float(ky)) for (kx, ky) in kpts]
    canon_kpts = canonical_sort_kpts(raw_flipped)
    return flipped_img, canon_kpts, w, h

def add_manga_noise(image):
    """
    Simulates scan contrast variations and screentone noise.
    """
    img = image.copy()
    alpha = np.random.uniform(0.85, 1.15)
    beta = np.random.uniform(-12, 12)
    img = np.clip(alpha * img + beta, 0, 255).astype(np.uint8)
    return img

def format_yolo_label(class_id, w, h, kpts):
    """
    YOLO Pose label format:
    <class-index> <x_center> <y_center> <width> <height> <px1> <py1> <v1> <px2> <py2> <v2>
    Coordinates normalized [0, 1].
    """
    xc, yc = 0.5, 0.5
    bw, bh = 1.0, 1.0
    
    parts = [str(class_id), f"{xc:.6f}", f"{yc:.6f}", f"{bw:.6f}", f"{bh:.6f}"]
    
    if class_id == 0 and len(kpts) >= 2:
        # Guarantee canonical order before writing
        ordered_kpts = canonical_sort_kpts(kpts)
        k1_x = max(0.0, min(1.0, ordered_kpts[0][0] / float(w)))
        k1_y = max(0.0, min(1.0, ordered_kpts[0][1] / float(h)))
        k2_x = max(0.0, min(1.0, ordered_kpts[1][0] / float(w)))
        k2_y = max(0.0, min(1.0, ordered_kpts[1][1] / float(h)))
        parts.extend([f"{k1_x:.6f}", f"{k1_y:.6f}", "2", f"{k2_x:.6f}", f"{k2_y:.6f}", "2"])
    else:
        # Single: 0 keypoints (visibility = 0)
        parts.extend(["0.000000", "0.000000", "0", "0.000000", "0.000000", "0"])
        
    return " ".join(parts) + "\n"

def main():
    print(">> Preparing YOLO-Pose Ground Truth Dataset with CANONICAL SORTING...")
    
    # 1. Clean export directory
    if EXPORT_DIR.exists():
        for item in EXPORT_DIR.iterdir():
            if item.is_dir():
                shutil.rmtree(item)
            elif item.name != "build_notebook.py" and item.name != "train_manga_waist_model.ipynb":
                item.unlink()
                
    for split in ["train", "val"]:
        (YOLO_DIR / "images" / split).mkdir(parents=True, exist_ok=True)
        (YOLO_DIR / "labels" / split).mkdir(parents=True, exist_ok=True)
        
    conjoined_items = []
    single_items = []
    
    for batch_name in BATCH_NAMES:
        batch_dir = DATASET_BASE / batch_name
        manifest_path = batch_dir / "manifest.json"
        verified_dir = batch_dir / "verified_annotated"
        images_dir = batch_dir / "images"
        
        if not manifest_path.exists():
            print(f"Warning: Manifest not found for {batch_name}")
            continue
            
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
            
        print(f"Loading {batch_name}: {len(manifest)} total items in manifest...")
        
        for item in manifest:
            if item.get("is_discarded", False):
                continue
            bid = item["bubble_id"]
            annot_file = verified_dir / f"{bid}.json"
            if not annot_file.exists():
                annot_file = batch_dir / "annotations" / f"{bid}.json"
            if not annot_file.exists():
                continue
                
            img_path = None
            for sub in ["images", "conjoined", "non_annotated"]:
                p = batch_dir / sub / item["filename"]
                if p.exists():
                    img_path = p
                    break
            if not img_path:
                continue
                
            with open(annot_file, "r", encoding="utf-8") as af:
                adata = json.load(af)
            
            is_conj = adata.get("is_conjoined", False)
            cps = adata.get("crunch_points", [])
            lines = adata.get("dividing_lines", [])
            
            kpts = []
            if is_conj:
                if len(cps) >= 2:
                    kpts = [(float(cps[0]["center"][0]), float(cps[0]["center"][1])),
                            (float(cps[1]["center"][0]), float(cps[1]["center"][1]))]
                elif len(lines) >= 1 and len(lines[0].get("points", [])) >= 2:
                    p1 = lines[0]["points"][0]
                    p2 = lines[0]["points"][1]
                    kpts = [(float(p1[0]), float(p1[1])), (float(p2[0]), float(p2[1]))]
                
                if len(kpts) == 2:
                    kpts = canonical_sort_kpts(kpts)
            
            entry = {
                "bubble_id": bid,
                "filename": item["filename"],
                "src_img_path": img_path,
                "is_conjoined": is_conj,
                "keypoints": kpts
            }
            
            if is_conj and len(kpts) == 2:
                conjoined_items.append(entry)
            elif not is_conj:
                single_items.append(entry)

    print(f"Total Combined Valid Verified: Conjoined={len(conjoined_items)}, Single={len(single_items)}")
    
    # 2. Stratified Split (100 Conjoined + 500 Single for Pure Val Set)
    random.shuffle(conjoined_items)
    random.shuffle(single_items)
    
    val_conjoined = conjoined_items[:100]
    train_conjoined = conjoined_items[100:]
    
    val_single = single_items[:500]
    # Sample single for training to achieve optimal ~1:1 balance against 7x augmented conjoined
    target_train_conjoined_aug = len(train_conjoined) * 7
    train_single = single_items[500:500 + target_train_conjoined_aug]
    
    print(f"Combined Split: Train Conjoined={len(train_conjoined)} (-> {target_train_conjoined_aug} augmented), Train Single={len(train_single)}")
    print(f"Combined Split: Val Conjoined={len(val_conjoined)}, Val Single={len(val_single)}")
    
    # 3. Export Val Set (Strictly untouched, zero augmentation, canonically sorted keypoints)
    for entry in val_conjoined + val_single:
        src_img_path = entry["src_img_path"]
        img = cv2.imread(str(src_img_path))
        if img is None:
            continue
        h, w = img.shape[:2]
        class_id = 0 if entry["is_conjoined"] else 1
        
        dst_name = f"{entry['bubble_id']}.png"
        lbl_name = f"{entry['bubble_id']}.txt"
        
        cv2.imwrite(str(YOLO_DIR / "images" / "val" / dst_name), img)
        lbl_content = format_yolo_label(class_id, w, h, entry["keypoints"])
        with open(YOLO_DIR / "labels" / "val" / lbl_name, "w", encoding="utf-8") as lf:
            lf.write(lbl_content)
            
    # 4. Export Train Single
    for entry in train_single:
        src_img_path = entry["src_img_path"]
        img = cv2.imread(str(src_img_path))
        if img is None:
            continue
        h, w = img.shape[:2]
        dst_name = f"{entry['bubble_id']}.png"
        lbl_name = f"{entry['bubble_id']}.txt"
        
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / dst_name), img)
        lbl_content = format_yolo_label(1, w, h, [])
        with open(YOLO_DIR / "labels" / "train" / lbl_name, "w", encoding="utf-8") as lf:
            lf.write(lbl_content)
            
    # 5. Export Train Conjoined with 7x Deterministic & Canonically Sorted Augmentations
    aug_count = 0
    for entry in train_conjoined:
        src_img_path = entry["src_img_path"]
        img = cv2.imread(str(src_img_path))
        if img is None:
            continue
        h, w = img.shape[:2]
        bid = entry["bubble_id"]
        kpts = canonical_sort_kpts(entry["keypoints"])
        
        # 5a. Original (Canonically sorted)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_orig.png"), img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_orig.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, w, h, kpts))
        aug_count += 1
        
        # 5b. Horizontal Flip (Canonically re-sorted!)
        f_img, f_kpts, f_w, f_h = flip_image_and_keypoints(img, kpts)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_flip.png"), f_img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_flip.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, f_w, f_h, f_kpts))
        aug_count += 1
        
        # 5c. Rotation +6 deg
        r1_img, r1_kpts, r1_w, r1_h = rotate_image_and_keypoints(img, kpts, 6.0)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_rot_p6.png"), r1_img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_rot_p6.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, r1_w, r1_h, r1_kpts))
        aug_count += 1
        
        # 5d. Rotation -6 deg
        r2_img, r2_kpts, r2_w, r2_h = rotate_image_and_keypoints(img, kpts, -6.0)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_rot_m6.png"), r2_img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_rot_m6.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, r2_w, r2_h, r2_kpts))
        aug_count += 1

        # 5e. Rotation +12 deg
        r3_img, r3_kpts, r3_w, r3_h = rotate_image_and_keypoints(img, kpts, 12.0)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_rot_p12.png"), r3_img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_rot_p12.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, r3_w, r3_h, r3_kpts))
        aug_count += 1
        
        # 5f. Rotation -12 deg
        r4_img, r4_kpts, r4_w, r4_h = rotate_image_and_keypoints(img, kpts, -12.0)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_rot_m12.png"), r4_img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_rot_m12.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, r4_w, r4_h, r4_kpts))
        aug_count += 1
        
        # 5g. Manga Scan Photometric Noise
        noise_img = add_manga_noise(img)
        cv2.imwrite(str(YOLO_DIR / "images" / "train" / f"{bid}_noise.png"), noise_img)
        with open(YOLO_DIR / "labels" / "train" / f"{bid}_noise.txt", "w", encoding="utf-8") as lf:
            lf.write(format_yolo_label(0, w, h, kpts))
        aug_count += 1

    print(f">> Augmentation Complete! Augmented Conjoined in Train: {aug_count}")
    train_total = len(list((YOLO_DIR / "images" / "train").glob("*.png")))
    val_total = len(list((YOLO_DIR / "images" / "val").glob("*.png")))
    print(f">> Total Training Images: {train_total} ({aug_count} Conjoined / {len(train_single)} Single)")
    print(f">> Total Validation Images: {val_total} ({len(val_conjoined)} Conjoined / {len(val_single)} Single)")
    
    # 6. Write data.yaml
    yaml_content = f"""path: ./bubble_dataset
train: images/train
val: images/val
kpt_shape: [2, 3]
flip_idx: [1, 0]
names:
  0: conjoined
  1: single
"""
    with open(YOLO_DIR / "data.yaml", "w", encoding="utf-8") as yf:
        yf.write(yaml_content)
    with open(EXPORT_DIR / "data.yaml", "w", encoding="utf-8") as yf:
        yf.write(yaml_content)

    # 7. Zip into bubble_dataset.zip
    zip_path = EXPORT_DIR / "bubble_dataset.zip"
    print(f">> Compressing into {zip_path}...")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(YOLO_DIR):
            for file in files:
                abs_f = Path(root) / file
                rel_f = abs_f.relative_to(EXPORT_DIR)
                zf.write(abs_f, rel_f)
                
    zip_size_mb = zip_path.stat().st_size / (1024.0 * 1024.0)
    print(f">> SUCCESS! bubble_dataset.zip created: {zip_size_mb:.2f} MB")

if __name__ == "__main__":
    main()
