import json
import argparse
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description="Generate HTML inspection gallery for a bubble dataset batch")
    parser.add_argument("--base_dir", type=str, default=r"d:\C\KisaraTranslator\method3\bubble_dataset",
                        help="Root dataset directory")
    parser.add_argument("--batch_name", type=str, default="batch_01",
                        help="Batch identifier (e.g. batch_01, batch_02)")
    args = parser.parse_args()

    batch_dir = Path(args.base_dir) / args.batch_name
    manifest_file = batch_dir / "manifest.json"
    if not manifest_file.exists():
        print(f"manifest.json not found in {batch_dir}")
        return

    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))

    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Method 3 — Bubble Inspector [{args.batch_name}]</title>
<style>
  body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #121214; color: #e0e0e0; margin: 0; padding: 20px; }}
  header {{ margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid #2d2d34; }}
  h1 {{ margin: 0 0 8px 0; font-size: 22px; color: #00e5ff; }}
  .stats {{ font-size: 14px; color: #a0a0ab; }}
  .badge {{ background: #1f2937; border: 1px solid #374151; padding: 4px 10px; border-radius: 12px; margin-right: 8px; font-weight: 600; color: #38bdf8; }}
  .grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }}
  .card {{ background: #1e1e24; border: 1px solid #2e2e38; border-radius: 8px; overflow: hidden; display: flex; flex-direction: column; }}
  .img-box {{ background: #2a2a35; padding: 12px; display: flex; justify-content: center; align-items: center; min-height: 220px; max-height: 280px; overflow: hidden; }}
  .img-box img {{ max-width: 100%; max-height: 250px; object-fit: contain; border-radius: 4px; box-shadow: 0 2px 8px rgba(0,0,0,0.5); }}
  .meta {{ padding: 10px 12px; font-size: 12px; border-top: 1px solid #2e2e38; background: #18181f; }}
  .meta-title {{ font-weight: 700; color: #f3f4f6; margin-bottom: 4px; font-size: 13px; }}
  .meta-sub {{ color: #9ca3af; line-height: 1.4; word-break: break-all; }}
  .conf {{ color: #10b981; font-weight: 600; }}
</style>
</head>
<body>
<header>
  <h1>Method 3 — Bubble Inspector [{args.batch_name}]</h1>
  <div class="stats">
    <span class="badge">Batch: {args.batch_name}</span>
    <span class="badge">{len(manifest)} Bubbles</span>
    <span class="badge">Raw Crops (4px Padding)</span>
  </div>
</header>
<div class="grid">
"""

    for item in manifest:
        html += f"""
  <div class="card">
    <div class="img-box">
      <img src="images/{item['filename']}" alt="{item['bubble_id']}" loading="lazy">
    </div>
    <div class="meta">
      <div class="meta-title">{item['bubble_id']} <span class="conf">{int(item['confidence']*100)}%</span></div>
      <div class="meta-sub"><b>Dim:</b> {item['crop_dimensions']['width']}x{item['crop_dimensions']['height']} (Pad: {item['padding_used']}px)<br><b>Source:</b> {item['page_name']}</div>
    </div>
  </div>
"""

    html += """
</div>
</body>
</html>
"""

    out_file = batch_dir / "inspect_gallery.html"
    out_file.write_text(html, encoding="utf-8")
    print(f"Generated inspection gallery: {out_file}")

if __name__ == "__main__":
    main()
