import os
import sys
import json
import shutil
from pathlib import Path

BASE_DIR = Path("D:/C/KisaraTranslator/method3")
STAGING_DIR = BASE_DIR / "mangadb_staging"
if STAGING_DIR.exists():
    shutil.rmtree(STAGING_DIR)
STAGING_DIR.mkdir(parents=True, exist_ok=True)

# 1. Load metadata for batch 04
with open(BASE_DIR / "raw_manga_batch_04" / "cbz" / "metadata.json", encoding="utf-8") as f:
    b4_meta = json.load(f)

# 2. Load metadata for batch 02
with open(BASE_DIR / "raw_manga_batch_02" / "cbz" / "metadata.json", encoding="utf-8") as f:
    b2_meta = json.load(f)

def clean_title(title_info: str) -> str:
    # e.g. "NHentai (JA)/[Anthology] Change H Orange/Chapter.cbz" -> "Change H Orange"
    parts = title_info.split("/")
    if len(parts) >= 2:
        t = parts[1]
    else:
        t = parts[0]
    t = t.replace("[Anthology]", "").replace("Chapter.cbz", "").strip()
    return t.strip()

b4_titles = {}
for i in range(1, 15):
    k = f"cbz_{i:02d}.cbz"
    if k in b4_meta:
        b4_titles[f"c{i:02d}"] = clean_title(b4_meta[k]["title_info"])

b2_titles = {}
for i in range(1, 18):
    k = f"cbz_{i:02d}.cbz"
    if k in b2_meta:
        b2_titles[f"c{i:02d}"] = clean_title(b2_meta[k]["title_info"])

# Curated high-yield pages with conjoined bubbles and clean dialogue
curated_b4 = [
    ("c01_p008.webp", "Kansai Juujin"),
    ("c01_p009.webp", "Kansai Juujin"),
    ("c02_p005.webp", "Toilet no Mizu"),
    ("c03_p004.webp", "Toaru Oyako"),
    ("c04_p006.webp", "SasuSaku"),
    ("c04_p010.webp", "SasuSaku"),
    ("c05_p007.webp", "Onee-chan ne"),
    ("c05_p009.webp", "Onee-chan ne"),
    ("c05_p014.webp", "Onee-chan ne"),
    ("c06_p045.webp", "Change H Orange"),
    ("c06_p067.webp", "Change H Orange"),
    ("c06_p167.webp", "Change H Orange"),
    ("c06_p183.webp", "Change H Orange"),
    ("c07_p013.webp", "Change H Red"),
    ("c07_p020.webp", "Change H Red"),
    ("c07_p025.webp", "Change H Red"),
    ("c07_p037.webp", "Change H Red"),
    ("c07_p040.webp", "Change H Red"),
    ("c07_p073.webp", "Change H Red"),
    ("c07_p199.webp", "Change H Red"),
    ("c08_p007.webp", "Mahou Shoujo Mizukara"),
    ("c08_p010.webp", "Mahou Shoujo Mizukara"),
    ("c09_p002.webp", "Pokemon Arceus"),
    ("c10_p002.webp", "Request Itadaita Mono"),
    ("c11_p002.webp", "Futanari Mara-chan"),
    ("c12_p006.webp", "Watashi Haha no Moto"),
    ("c13_p018.jpg", "SAIWAI"),
    ("c13_p025.jpg", "SAIWAI"),
    ("c14_p008.webp", "Yamadanchi"),
    ("c14_p016.webp", "Yamadanchi")
]

curated_b2 = [
    ("c01_p019.webp", "Nouka ni Tensei"),
    ("c01_p037.webp", "Nouka ni Tensei"),
    ("c05_p022.webp", "Teisou Gyakuten"),
    ("c06_p006.webp", "Teisou Gyakuten"),
    ("c10_p002.jpg", "NICO the Demon Head"),
    ("c12_p001.jpg", "Bibibitto Izumi-kun"),
    ("c13_p002.jpg", "Aoi Shoujo to Ookami"),
    ("c14_p002.webp", "Ender Geister"),
    ("c17_p022.png", "Ichizoku wo Horobosareta")
]

b4_src_dir = BASE_DIR / "raw_manga_batch_04" / "pages_with_bubbles"
b2_src_dir = BASE_DIR / "raw_manga_batch_02" / "pages_with_bubbles"

dataset_index = []
featured_filenames = []

print("Staging Batch 04 curated pages...")
for fn, title in curated_b4:
    src_file = b4_src_dir / fn
    if src_file.exists():
        target_fn = f"b4_{fn}"
        shutil.copy2(src_file, STAGING_DIR / target_fn)
        dataset_index.append({
            "filename": target_fn,
            "title": title,
            "series": title
        })
        if title not in [x["series"] for x in dataset_index[:-1]]:
            featured_filenames.append(target_fn)
    else:
        print(f"Warning: {src_file} does not exist")

print("Staging Batch 02 curated pages...")
for fn, title in curated_b2:
    src_file = b2_src_dir / fn
    if src_file.exists():
        target_fn = f"b2_{fn}"
        shutil.copy2(src_file, STAGING_DIR / target_fn)
        dataset_index.append({
            "filename": target_fn,
            "title": title,
            "series": title
        })
        if title not in [x["series"] for x in dataset_index[:-1]]:
            featured_filenames.append(target_fn)
    else:
        print(f"Warning: {src_file} does not exist")

with open(STAGING_DIR / "dataset_index.json", "w", encoding="utf-8") as f:
    json.dump(dataset_index, f, indent=2, ensure_ascii=False)

print(f"\nSuccessfully staged {len(dataset_index)} pages into {STAGING_DIR}")
print(f"Total series represented: {len(set(x['series'] for x in dataset_index))}")
print(f"Featured filenames: {featured_filenames}")
