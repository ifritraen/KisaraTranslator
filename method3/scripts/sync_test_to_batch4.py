import json
import shutil
from pathlib import Path

BASE_DIR = Path("D:/C/KisaraTranslator/method3")
TEST_DIR = BASE_DIR / "bubble_dataset" / "test"
BATCH_04_DIR = BASE_DIR / "bubble_dataset" / "batch_04"

def sync_test_to_batch4():
    print("=" * 60)
    print("STEP 1: Syncing test annotations into batch_04")
    print("=" * 60)

    with open(TEST_DIR / "manifest.json", encoding="utf-8") as f:
        test_manifest = json.load(f)

    with open(BATCH_04_DIR / "manifest.json", encoding="utf-8") as f:
        b4_manifest = json.load(f)

    # Index batch_04 by bubble_id
    b4_by_id = {item["bubble_id"]: idx for idx, item in enumerate(b4_manifest)}

    test_annot_dir = TEST_DIR / "verified_annotated"
    b4_annot_dir = BATCH_04_DIR / "verified_annotated"
    b4_compat_dir = BATCH_04_DIR / "annotations"
    b4_conj_dir = BATCH_04_DIR / "conjoined"
    b4_disc_dir = BATCH_04_DIR / "discarded"
    b4_img_dir = BATCH_04_DIR / "images"

    for d in [b4_annot_dir, b4_compat_dir, b4_conj_dir, b4_disc_dir]:
        d.mkdir(parents=True, exist_ok=True)

    synced_count = 0
    synced_conj = 0
    synced_single = 0
    synced_disc = 0

    for t_item in test_manifest:
        orig_b4_id = t_item.get("original_batch4_id")
        if not orig_b4_id or orig_b4_id not in b4_by_id:
            continue

        b4_idx = b4_by_id[orig_b4_id]
        b4_item = b4_manifest[b4_idx]

        # Check if item was annotated or discarded in test
        if t_item.get("status") == "verified" or t_item.get("is_conjoined") is not None or t_item.get("is_discarded"):
            # Update manifest entry
            b4_item["is_conjoined"] = t_item.get("is_conjoined")
            b4_item["is_discarded"] = t_item.get("is_discarded", False)
            b4_item["discard_reason"] = t_item.get("discard_reason")
            b4_item["status"] = t_item.get("status")
            b4_item["annotated_at"] = t_item.get("annotated_at")
            synced_count += 1

            if t_item.get("is_discarded"):
                synced_disc += 1
                # Move image in batch_04 to discarded if exists
                b4_fn = b4_item["filename"]
                src_p = b4_img_dir / b4_fn
                if src_p.exists():
                    try:
                        shutil.copy2(src_p, b4_disc_dir / b4_fn)
                    except Exception:
                        pass
                continue

            if t_item.get("is_conjoined") is True:
                synced_conj += 1
                # Copy to conjoined/
                b4_fn = b4_item["filename"]
                src_p = b4_img_dir / b4_fn
                if src_p.exists():
                    try:
                        shutil.copy2(src_p, b4_conj_dir / b4_fn)
                    except Exception:
                        pass
            elif t_item.get("is_conjoined") is False:
                synced_single += 1

            # Sync annotation JSON
            t_json_p = test_annot_dir / f"{t_item['bubble_id']}.json"
            if t_json_p.exists():
                with open(t_json_p, encoding="utf-8") as f:
                    annot_data = json.load(f)

                annot_data["bubble_id"] = orig_b4_id
                annot_data["batch"] = "batch_04"
                annot_data["filename"] = b4_item["filename"]

                target_json = b4_annot_dir / f"{orig_b4_id}.json"
                compat_json = b4_compat_dir / f"{orig_b4_id}.json"

                with open(target_json, "w", encoding="utf-8") as f:
                    json.dump(annot_data, f, indent=2)
                with open(compat_json, "w", encoding="utf-8") as f:
                    json.dump(annot_data, f, indent=2)

    # Save updated batch_04 manifest
    with open(BATCH_04_DIR / "manifest.json", "w", encoding="utf-8") as f:
        json.dump(b4_manifest, f, indent=2)

    print(f"Sync complete:")
    print(f"  Total test items synced to batch_04: {synced_count}")
    print(f"    - Conjoined: {synced_conj}")
    print(f"    - Single:    {synced_single}")
    print(f"    - Discarded: {synced_disc}")
    print("=" * 60)

if __name__ == "__main__":
    sync_test_to_batch4()
