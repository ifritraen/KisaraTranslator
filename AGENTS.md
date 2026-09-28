# AGENTS.md — KisaraTranslator & Crunch Lab

## 🚨 MANDATORY CONSTRAINTS
- **No Polling**: NEVER loop `manage_task`, `status`, or shell loops. Wait for reactive wakeups.
- **No UI Automation via ADB**: NEVER run `input tap`, `swipe`, `am start`. ADB is strictly for `install -r`, `pull`, and `logcat -d`.
- **Gradle Safety**: Always use `./gradlew` (never `.bat`), `--offline --build-cache --parallel`. Run only when explicitly asked. If hung, `./gradlew --stop`.
- **Universal Impact & Refinement**: Always think twice about how an algorithmic change will affect all manga pages universally before applying it. Avoid arbitrary local thresholds or ad-hoc patches; verify whether the solution needs geometric refinement based on physical laws of typography (e.g. bubble capacity bounds) to prevent edge-case regressions.

---

## 📱 APPS IN CODEBASE
- **Main App (`:app`)**: `./gradlew assembleDebug` | Install: `app/build/outputs/apk/debug/app-debug.apk`
- **Crunch Lab (`:crunchlab`)**: `./gradlew :crunchlab:assembleDebug` | Install: `crunchlab/build/outputs/apk/debug/crunchlab-debug.apk`
- **Method 3 Annotator (`:method3-annotator`)**: `./gradlew :method3-annotator:assembleDebug` | Server: `python method3/server/server.py` | Details: `method3/README.md`

---

## 📐 CRUNCH LAB PROTOCOL (`com.raen.crunchlab`)
When prompted with **"check"**:
1. Pull artifacts: `adb -s <active_device> pull /sdcard/Download/CrunchLab/. D:/C/KisaraTranslator/.aaa/crunch_export/`
2. Inspect telemetry (`crunch_telemetry.json`) and step images: `m1_*.png`, `m1_5_*.png`, `m2_1_all_vertical_lines.png` (primary M2 check), `m3_*.png`.

---

## 🎯 CRUNCH LAB PIPELINE SPECIFICATION & INVARIANTS

### 1. Module Pipeline & Goals
* **Module 1.1 (Heatmap)**: ComicTextDetector raw float text probability map ($1024 \times 1024$). Highlights ink regions with $p \ge 0.20$.
* **Module 1.2 (Pass 1 Detection)**: Global sensitive CTD detection extracts atomic character boxes (`rawPass1Boxes`) + continuous heatmap island recovery. Recombines split character radicals into atomic character em-boxes and feeds directly into downstream modules instantaneously (M1.3 directional boundary probing eliminated).
* **Module 1.5 (Categorization)**: Detects speech bubbles via YOLO-seg / Manga109. Containment assigns `BUBBLED`. Unbubbled text is spatially clustered first, then scored via stroke caliber, fill ratio, and aspect regularity to differentiate `ORPHAN` (dialogue/narration on art) vs `SFX` (sound effects, brush art).
* **Module 2.X (Vertical Lines)**: Stitches character boxes into full vertical lines. 
  - **1 Line Per Column**: Inside any speech bubble, a vertical column has zero breaks/segments from top to bottom.
  - **Uniform Width**: All columns in a bubble share uniform width $= \text{median}(\text{char.width})$.
  - **CTD Atomic Box Grounding**: Line boundaries and seam trims must NEVER cut through an underlying CTD character box; boundaries snap to the whitespace gutter between characters.
  - **Strict Containment**: In-bubble lines are 100% contained within the bubble boundary (zero bleeding outside).
  - **Zero Crossover**: Adjacent columns on the page maintain 0-pixel overlap.
* **Module 3.X (Waist Crunch)**: Conjoined speech bubble separation. YOLOv8-pose keypoint regression finds approximate waist ($\pm 8\text{px}$); OpenCV convexity defects snap to exact 1px ink notch. Laser seam cut divides the bubble and partitions Module 2 vertical lines into Lobe A and Lobe B.

### 2. Core Invariants (Inviolable)
1. **CTD Box Atomicity**: CTD Pass 1 boxes are indivisible units of ink. Never place a column boundary cut that slices across an atomic CTD character box.
2. **In-Bubble Isolation**: Speech bubble lines must never merge with unbubbled orphan/SFX lines.
3. **No Fragmentation Inside Bubbles**: All character boxes sharing an X corridor in a bubble unite into 1 continuous vertical strip.
4. **Uniform Column Width**: All columns in a bubble share uniform width $= \text{median}(\text{char.width})$.
5. **Strict Containment**: In-bubble lines are 100% contained within the bubble boundary (zero bleeding outside).
6. **Zero Crossover**: Adjacent columns on the page maintain 0-pixel overlap. Seams snap to whitespace gutters.
