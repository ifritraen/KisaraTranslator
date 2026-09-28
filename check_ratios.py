import json
with open('.aaa/crunch_export/crunch_telemetry.json', encoding='utf-8') as f:
    data = json.load(f)
bubbles = data['module_1_ctd']['bubbles']
lines = data['module_1_ctd']['lines']
for b in bubbles:
    b_l, b_t, b_r, b_b = b['left'], b['top'], b['right'], b['bottom']
    b_idx = b['index']
    for l in lines:
        lid = l['id']
        interL = max(l['left'], b_l)
        interT = max(l['top'], b_t)
        interR = min(l['right'], b_r)
        interB = min(l['bottom'], b_b)
        interArea = max(0, interR - interL) * max(0, interB - interT)
        boxArea = (l['right'] - l['left']) * (l['bottom'] - l['top'])
        ratio = interArea / boxArea if boxArea > 0 else 0
        if 0.1 <= ratio < 0.95:
            print(f'Bubble {b_idx} box {lid}: ratio={ratio:.3f}, rect={[l["left"], l["top"], l["right"], l["bottom"]]}')
