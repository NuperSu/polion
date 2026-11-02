from pathlib import Path
import argparse
from PIL import Image, ImageChops, ImageStat, ImageDraw
import csv
import html

parser = argparse.ArgumentParser(description="Create full-size Phase 0 / shared-renderer comparison images.")
parser.add_argument("baseline", type=Path, help="Directory of manifest-verified Phase 0 reference PNGs")
parser.add_argument("current", type=Path, help="Directory of current shared-fixture PNGs")
parser.add_argument("output", type=Path, help="Output directory, separate from both capture directories")
args = parser.parse_args()
baseline, current, out = args.baseline.resolve(), args.current.resolve(), args.output.resolve()
if out in (baseline, current):
    parser.error("Output must be separate from both capture directories")
if not baseline.is_dir() or not current.is_dir():
    parser.error("Both capture directories must exist")
out.mkdir(parents=True, exist_ok=True)
rows = []
for ref in sorted(baseline.glob('*.png')):
    # The agreed inline editor replaces the Phase 0 alert-dialog capture.
    current_name = ref.name.replace("-coin-alert-dialog.png", "-coin-alert-editor.png")
    target = current / current_name
    if not target.exists():
        rows.append((ref.name, current_name, 'missing', '', ''))
        continue
    a, b = Image.open(ref).convert('RGB'), Image.open(target).convert('RGB')
    if a.size != b.size:
        rows.append((ref.name, current_name, 'size mismatch', str(a.size), str(b.size)))
        continue
    diff = ImageChops.difference(a, b)
    stat = ImageStat.Stat(diff)
    changed = sum(1 for p in diff.getdata() if max(p) > 8) / (a.width * a.height)
    mean = sum(stat.mean) / 3
    rows.append((ref.name, current_name, 'compared', f'{changed:.6f}', f'{mean:.3f}'))
    # Full-size paired images support direct review; the third panel amplifies differences.
    sheet = Image.new('RGB', (a.width * 3, a.height + 24), 'white')
    draw = ImageDraw.Draw(sheet)
    for x, label, im in ((0, 'Phase 0 reference', a), (a.width, 'Phase 1 shared fixture', b), (2*a.width, 'Difference x4', diff.point(lambda v: min(255, v*4)))):
        draw.text((x + 4, 4), label, fill='black')
        sheet.paste(im, (x, 24))
    sheet.save(out / current_name)
with (out / 'metrics.csv').open('w', newline='') as f:
    writer = csv.writer(f)
    writer.writerow(('reference_capture', 'current_capture', 'status', 'fraction_pixels_delta_gt_8', 'mean_absolute_channel_delta'))
    writer.writerows(rows)
print('baseline', len(rows), 'compared', sum(r[2] == 'compared' for r in rows), 'missing or different size', sum(r[2] != 'compared' for r in rows))

with (out / "index.html").open("w", encoding="utf-8") as f:
    f.write("<!doctype html><meta charset='utf-8'><title>Phase 1 capture comparisons</title>")
    f.write("<h1>Phase 1 capture comparisons</h1><p>Each image shows the Phase 0 reference, shared fixture, and amplified difference. Review pending.</p><ul>")
    for reference, name, status, _, _ in rows:
        if status == "compared":
            escaped = html.escape(name, quote=True)
            f.write(f'<li><a href="{escaped}">{escaped}</a> (reference: {html.escape(reference)})</li>')
    f.write("</ul>")
