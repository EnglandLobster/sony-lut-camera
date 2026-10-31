#!/usr/bin/env python3
"""Small additional .cube fixtures; no camera communication."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'out' / 'gpu-lut-fixtures'
root.mkdir(parents=True, exist_ok=True)

def cube(name, n, fn):
    with (root / name).open('w') as f:
        f.write(f'LUT_3D_SIZE {n}\n')
        for b in range(n):
            for g in range(n):
                for r in range(n):
                    f.write(' '.join(f'{v:.10f}' for v in fn(r/(n-1), g/(n-1), b/(n-1)))+'\n')

# Smooth mixed-channel table plus a coarse product table: the latter gives
# ~128 at neutral mid-grey with tetrahedral, versus ~64 with trilinear.
cube('CROSS17.CUB', 17, lambda r,g,b: (.04+.7*r+.25*g*b, .05+.75*g+.2*r*(1-b), -.08+.8*b+.3*r*g))
cube('TETRA2.CUB', 2, lambda r,g,b: (g*b,r*b,r*g))
# Signed values and clipping exercise the entire production Q12 range.
cube('EXTREME.CUB', 2, lambda r,g,b: (32*r-16, 32*g-16, 32*b-16))
cube('ID65.CUB', 65, lambda r,g,b: (r,g,b))
print(root)
