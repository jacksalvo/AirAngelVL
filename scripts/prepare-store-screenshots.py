"""Pad genuine phone screenshots to Play's 2:1 limit without changing content.
Requires Pillow. Usage: python scripts/prepare-store-screenshots.py INPUT1 INPUT2
The source files are never modified. Output goes into store/graphics/phone-screenshots.
"""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('sources', nargs=2, type=Path)
args = parser.parse_args()
out = Path(__file__).resolve().parents[1] / 'store' / 'graphics' / 'phone-screenshots'
out.mkdir(parents=True, exist_ok=True)
results = []
for index, source in enumerate(args.sources, 1):
    with Image.open(source) as loaded:
        loaded.load()
        if 'A' in loaded.getbands() and loaded.getchannel('A').getextrema() != (255, 255):
            raise ValueError('Source has transparency; refusing to change its appearance.')
        original = loaded.convert('RGB')
    width, height = original.size
    target_width = max(width, (height + 1) // 2)
    target_height = max(height, (width + 1) // 2)
    if min(target_width, target_height) < 320 or max(target_width, target_height) > 3840:
        raise ValueError('Source dimensions need more than padding; original remains unchanged.')
    left = (target_width - width) // 2
    top = (target_height - height) // 2
    padded = Image.new('RGB', (target_width, target_height), (0, 0, 0))
    padded.paste(original, (left, top))
    destination = out / f'{index:02}-training-preview.png'
    padded.save(destination, format='PNG', optimize=True)
    with Image.open(destination) as verified:
        assert verified.mode == 'RGB'
        assert verified.crop((left, top, left + width, top + height)).tobytes() == original.tobytes()
        assert max(verified.size) <= min(verified.size) * 2
    results.append({'file': destination.name, 'source': source.name, 'originalSize': [width,height],
                    'outputSize': [target_width,target_height], 'paddingLeft':left, 'paddingTop':top,
                    'originalPixelsPreserved':True, 'sha256':hashlib.sha256(destination.read_bytes()).hexdigest()})
(out / 'verification.json').write_text(json.dumps(results, indent=2)+'\n', encoding='utf-8')
print(json.dumps(results, indent=2))
