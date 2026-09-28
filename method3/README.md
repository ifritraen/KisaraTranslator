# Method 3: Speech Bubble Dataset & Annotation Pipeline

## 1. Batch Harvesting from Mobile
1. **Source Location on Phone (INVIOLABLE)**:
   - Manga `.cbz` archives downloaded via Komikku are located on the phone at:
     `/sdcard/Aaaaa/Otaku/Komikku/downloads` (or `/sdcard/aaaaa/otaku/komikku/download`)
   - Always target the active wireless ADB device (e.g. `192.168.0.181:5555`).
2. **Pull Archives via ADB**:
   ```powershell
   adb -s <active_device> pull /sdcard/Aaaaa/Otaku/Komikku/downloads/. method3/raw_manga_batch_XX/cbz/
   ```
3. **Extract Pages & Filter 0-Bubble Pages**:
   - Extract images from `.cbz` files into `raw_manga_batch_XX/extracted_pages`.
   - Run bubble detection pass across extracted pages. Discard bubble-free splash, cover, or credit pages.
   - Save only positive pages with speech bubbles to `method3/raw_manga_batch_XX/pages_with_bubbles/`.
4. **Adaptive Crop Slicing (12–16px Padding)**:
   - Calculate adaptive padding scaled to bubble dimensions:
     ```python
     pad = int(np.clip(round(12 + (dim - 150) * (4.0 / 350.0)), 12, 16))
     ```
   - Crop bubbles from the raw manga pages with this padding to guarantee boundary ink context.
   - Save image crops to `method3/bubble_dataset/batch_XX/images/` and `method3/bubble_dataset/batch_XX/non_annotated/`.
   - Generate `manifest.json` with bubble metadata.

## 2. Annotation Server & Mobile Annotator App
1. **Local Sync Server**:
   - Run `python method3/server/server.py` (serves on `http://<LAN-IP>:8000`).
   - Handles `/api/manifest`, `/api/image/<id>`, `/api/save`, `/api/discard`, `/api/undo`, `/api/auto_suggest`, and `/api/status`.
2. **Multi-Batch Switching**:
   - Mobile app displays top bar batch toggle chip (`[batch_01 / batch_02]`).
   - Switching batches dynamically requests `/api/manifest?batch=batch_XX` and switches image directories.
3. **Instant Discard & Undo Quarantine**:
   - Discards immediately move files to `discarded/` directory without blocking dialogs.
   - Inline Snackbar `[UNDO]` button calls `/api/undo`, immediately restoring the bubble from quarantine.
4. **Adaptive Brightness for Dark/Aged Scans**:
   - For concave defect auto-suggest, dynamic thresholding is required:
     ```python
     t = int(min(225, max(140, np.percentile(gray, 95) * 0.90)))
     ```
5. **Multi-Cut Support for 3-Conjoined Bubbles**:
   - App supports multiple cut lines (`[+ Cut 2]`, `[- Cut 2]`).
   - Cut 1 uses neon lime, Cut 2 uses neon magenta, with distinct color-coded notch markers.
6. **Candidate Points & Magnetic Snapping**:
   - Server returns perimeter defect candidates. App renders subtle target markers with a 24dp magnetic snap radius when dragging notches.
7. **Verified Review Mode**:
   - Top bar FilterChip allows toggling between unannotated labeling and reviewing verified conjoined or all bubbles.

## 3. Canonical $\mathbb{R}^2$ Lexicographical Sorting (Critical for Training)
- **The 50/50 Swap Trap**:
  - If Point 1 and Point 2 are assigned in arbitrary order (e.g. random or clockwise), neural keypoint regressors experience conflicting gradient signals for identical visual features, forcing keypoints to collapse to the crop center `(0.5, 0.5)`.
- **Lexicographical Invariant**:
  - Always enforce $x_1 < x_2$ (or if $x_1 == x_2$, $y_1 \le y_2$) across all ground truth keypoints and datasets:
    ```python
    if pt1[0] > pt2[0] or (pt1[0] == pt2[0] and pt1[1] > pt2[1]):
        pt1, pt2 = pt2, pt1
    ```
- **Augmentation Rules**:
  - Disable horizontal flips during training (`fliplr=0.0`) or explicitly swap $P_1 \leftrightarrow P_2$ upon any horizontal mirror transform. Set `mosaic=0.0` to preserve accurate contour geometries.

## 4. Model Training & Dual-Stage Hybrid Pipeline
- **Stage 1: YOLOv8-Pose Keypoint Regression**:
  - Loss weights: `pose=25.0`, `box=1.0`, `cls=1.0`.
  - Class 0: `conjoined` (has 2 keypoints: Point A and Point B).
  - Class 1: `single` (0 keypoints).
  - **The "49.8% OKS Illusion"**: Ultralytics reports mAP averaged over all classes. Because `single` has 0 keypoints ($0.0\%$ mAP), an overall OKS mAP50 of $\approx 49.8\%$ means conjoined waist keypoint detection is actually performing at $\approx 99.6\%$ accuracy.
- **Stage 2: Local Contour Defect Refinement**:
  - Predictions from YOLOv8-pose provide rough waist locations ($\pm 8$px).
  - A fast local OpenCV contour defect search in a 15px radius around the predicted coordinates snaps coordinates to the exact 1-pixel ink notch.
