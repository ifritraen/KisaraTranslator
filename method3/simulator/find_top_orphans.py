import json

with open(r'D:\C\KisaraTranslator\method3\simulator\output\inpaint_sim_ledger.json', 'r') as f:
    ledger = json.load(f)

by_batch = {'batch1': [], 'batch2': [], 'batch3': [], 'batch4': []}
for r in ledger:
    b = r.get('batch', '')
    if b in by_batch:
        by_batch[b].append(r)

for b, items in by_batch.items():
    items.sort(key=lambda x: x.get('orphan_pixels_erased', 0), reverse=True)
    print(f'=== {b} Top Real Orphan Pages (by erased ink volume) ===')
    for x in items[:6]:
        print(f"{x['filename']}: {x['orphan_lines']} lines | {x.get('orphan_pixels_erased', 0)} ink pixels erased | {x['ctd_boxes']} ctd boxes")
