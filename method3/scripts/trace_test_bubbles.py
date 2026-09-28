import json
import sys

if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

target_ids = ['test_b00992', 'test_b00924', 'test_b00806']

with open('D:/C/KisaraTranslator/method3/bubble_dataset/test/manifest.json', encoding='utf-8') as f:
    test_manifest = json.load(f)

with open('D:/C/KisaraTranslator/method3/raw_manga_batch_04/cbz/metadata.json', encoding='utf-8') as f:
    cbz_meta = json.load(f)

for b_id in target_ids:
    item = next((x for x in test_manifest if x['bubble_id'] == b_id), None)
    if not item:
        print(f"{b_id}: Not found in test manifest")
        continue
    
    page_name = item.get('page_name')
    orig_b4_id = item.get('original_batch4_id')
    raw_bbox = item.get('raw_bbox')
    crop_bbox = item.get('crop_bbox')
    
    manga_info = 'Unknown'
    if page_name and page_name.startswith('c'):
        cbz_num = page_name.split('_')[0][1:]
        cbz_key = f"cbz_{cbz_num}.cbz"
        if cbz_key in cbz_meta:
            manga_info = cbz_meta[cbz_key].get('title_info')
            
    print(f"=== {b_id} ===")
    print(f"  Original Batch 4 ID: {orig_b4_id}")
    print(f"  Filename:            {item.get('filename')}")
    print(f"  Manga Source:        {manga_info}")
    print(f"  Page Name:           {page_name} (Page #{item.get('page_index')})")
    print(f"  Bubble on Page:      Bubble #{item.get('bubble_index_on_page')}")
    print(f"  Raw Bounding Box:    {raw_bbox}")
    print(f"  Crop BBox:           {crop_bbox}")
    print()
