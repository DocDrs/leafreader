#!/usr/bin/env python3
"""Original fixed-seed paper; no reference pixels/text. Android uses the committed PNG.
Optional art-source tool: Python 3 + NumPy + Pillow.
"""
from pathlib import Path
import hashlib, json
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1024, 1792
random = np.random.default_rng(140241)
def field(x, y):
    grid = random.normal(0, 1, (y, x)).astype(np.float32)
    data = np.asarray(Image.fromarray(grid).resize((W, H), Image.Resampling.BICUBIC))
    return (data - data.mean()) / data.std()
cloud = field(5, 9)*.55 + field(19, 33)*.65 + field(61, 107)*.65 + field(173, 303)*.35
yy, xx = np.mgrid[0:H, 0:W]
grain = field(420, 735)*.6 + random.normal(0, 1.8, (H, W))
# Preserve the original fine grain; keep the top even, with gentle downward shading.
# No reference image pixels are used. The full sheet is cover/cropped by the native reader.
y = yy/H
wear = 1.0*(xx/W)**4 + 3.2*(y*y*(3-2*y))
shade = np.clip(cloud + grain, -8, 8)
rgb = np.clip(np.array([219.,201.,167.4]) + shade[...,None]*np.array([.93,1.,1.1]) - wear[...,None]*np.array([1.,1.,1.6]),0,255).astype(np.uint8)
paper = Image.fromarray(rgb).convert('RGBA')
fibers = Image.new('RGBA',(W,H)); draw = ImageDraw.Draw(fibers)
for i in range(35000):
    x,y = random.uniform(0,W),random.uniform(0,H)
    radius = random.uniform(.25,1.1)
    color = (117,93,58,int(random.integers(7,18))) if i%3 else (250,238,207,int(random.integers(8,24)))
    draw.ellipse((x-radius,y-radius,x+radius,y+radius),fill=color)
# Very short, direction-neutral traces; no long horizontal scratch pattern.
for i in range(1500):
    x,y = random.uniform(0,W),random.uniform(0,H)
    length,angle = random.uniform(1,3.8),random.uniform(-np.pi,np.pi)
    draw.line([(x,y),(x+np.cos(angle)*length,y+np.sin(angle)*length)],fill=(124,100,65,12),width=1)
paper = Image.alpha_composite(paper,fibers.filter(ImageFilter.GaussianBlur(.38))).convert('RGB')
destination = Path(__file__).resolve().parents[1]/'app/src/main/res/drawable-nodpi/kraft_paper_texture.png'
destination.parent.mkdir(parents=True,exist_ok=True); paper.save(destination,optimize=True)
pixels = np.asarray(paper)
print(json.dumps({'resource':'res/drawable-nodpi/kraft_paper_texture.png','size':paper.size,
 'bytes':destination.stat().st_size,'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),
 'mean_rgb':pixels.mean(axis=(0,1)).round(2).tolist(),'std_rgb':pixels.std(axis=(0,1)).round(2).tolist(),
 'minimum_rgb':pixels.min(axis=(0,1)).tolist()},indent=2))
