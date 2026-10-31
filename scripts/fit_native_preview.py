#!/usr/bin/env python3
"""Fit preview-only Sony Q10 matrix/shared gamma candidates. No device writes.

Both orders are hypotheses: the native pixel order/domain must be calibrated on
camera. This is an sRGB approximation report, not a hardware color prediction.
Requires numpy, scipy, Pillow. Original .cube files are only read.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from scipy.optimize import least_squares


def load_cube(path):
    size = None
    domain = {"DOMAIN_MIN": [0., 0., 0.], "DOMAIN_MAX": [1., 1., 1.]}
    rows = []
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        parts = line.split("#", 1)[0].split()
        if not parts or parts[0] == "TITLE":
            continue
        if parts[0] == "LUT_3D_SIZE":
            if size is not None or len(parts) != 2:
                raise ValueError("duplicate/invalid size")
            size = int(parts[1])
        elif parts[0] in domain:
            if len(parts) != 4:
                raise ValueError("invalid domain")
            domain[parts[0]] = list(map(float, parts[1:]))
        else:
            if len(parts) != 3:
                raise ValueError("unsupported cube directive/row")
            rows.append(list(map(float, parts)))
    if size is None or not 2 <= size <= 65 or len(rows) != size**3:
        raise ValueError("invalid cube size or payload")
    cube = np.asarray(rows).reshape(size, size, size, 3)  # B,G,R; R fastest
    lo, hi = np.asarray(domain["DOMAIN_MIN"]), np.asarray(domain["DOMAIN_MAX"])
    if not np.all(np.isfinite(cube)) or not np.all(np.isfinite([lo, hi])) or np.any(hi <= lo):
        raise ValueError("nonfinite cube or invalid domain")
    return cube, lo, hi


def tetra(cube, lo, hi, rgb):
    """Floating point tetrahedral reference; NOT production Q12/Q14 rounding."""
    n = len(cube)
    p = np.clip((rgb-lo)/(hi-lo), 0, 1)*(n-1)
    base = np.minimum(np.floor(p).astype(int), n-2)
    frac = p-base
    order = np.argsort(-frac, axis=1, kind="stable")
    sorted_f = np.take_along_axis(frac, order, axis=1)
    vertices = [base.copy()]
    for k in range(3):
        nxt = vertices[-1].copy()
        nxt[np.arange(len(rgb)), order[:, k]] += 1
        vertices.append(nxt)
    weights = [1-sorted_f[:, 0], sorted_f[:, 0]-sorted_f[:, 1],
               sorted_f[:, 1]-sorted_f[:, 2], sorted_f[:, 2]]
    out = np.zeros_like(rgb, dtype=float)
    for v, w in zip(vertices, weights):
        out += cube[v[:, 2], v[:, 1], v[:, 0]]*w[:, None]
    return np.clip(out, 0, 1)


def curve_from_params(p, black, white):
    # Positive intervals make monotonicity structural, not a post-fit repair.
    increments = np.exp(p-np.max(p))
    return np.r_[black, black+(white-black)*np.cumsum(increments)/increments.sum()]


def apply(rgb, matrix, gamma, order):
    def g(x):
        return np.interp(np.clip(x, 0, 1), np.linspace(0, 1, len(gamma)), gamma)
    if order == "matrix_gamma":
        return g(rgb @ matrix.T)
    if order == "gamma_matrix":
        return np.clip(g(rgb) @ matrix.T, 0, 1)
    raise ValueError("unknown model order")


def lab(rgb):
    linear = np.where(rgb <= .04045, rgb/12.92, ((rgb+.055)/1.055)**2.4)
    xyz = linear @ np.array([[.4124564,.3575761,.1804375],
                            [.2126729,.7151522,.0721750],
                            [.0193339,.1191920,.9503041]]).T
    t = xyz/np.array([.95047,1,1.08883])
    d = 6/29
    f = np.where(t > d**3, np.cbrt(t), t/(3*d*d)+4/29)
    return np.column_stack([116*f[:,1]-16,500*(f[:,0]-f[:,1]),200*(f[:,1]-f[:,2])])


def metrics(target, actual):
    delta = np.linalg.norm(lab(target)-lab(actual), axis=1)
    return {"rgb_mae_255": float(np.abs(target-actual).mean()*255),
            "delta_e76_mean": float(delta.mean()), "delta_e76_p95": float(np.percentile(delta,95)),
            "delta_e76_max": float(delta.max())}


def fit(cube, lo, hi, order):
    # Independent uniform validation set is generated later; extra neutral and
    # near-neutral training samples prevent a saturated cube from dominating.
    rng = np.random.default_rng(6300)
    neutral = np.linspace(0,1,129)
    rgb = np.vstack([rng.random((2048,3)), np.repeat(neutral[:,None],3,axis=1),
                     np.clip(rng.random((512,1))+rng.normal(0,.07,(512,3)),0,1)])
    target = tetra(cube,lo,hi,rgb)
    diagonal = tetra(cube,lo,hi,np.repeat(np.linspace(0,1,33)[:,None],3,axis=1))
    curve = diagonal @ np.array([.2126,.7152,.0722])
    black, white = float(curve[0]), float(curve[-1])
    if white-black < .05:
        raise ValueError("shared monotone curve cannot represent this LUT")
    initial_steps = np.maximum(np.diff(curve),1e-5)
    x0 = np.r_[np.eye(3).ravel(), np.log(initial_steps/initial_steps.mean())]
    def residual(p):
        m = p[:9].reshape(3,3)
        g = curve_from_params(p[9:],black,white)
        # Weak regularization limits unstable color mixing and curve wiggles.
        return np.r_[(apply(rgb,m,g,order)-target).ravel(),
                     .05*(m-np.eye(3)).ravel(), .1*np.diff(g,2)]
    fitted = least_squares(residual,x0,bounds=(np.r_[np.full(9,-2.),np.full(32,-10.)],
                          np.r_[np.full(9,2.),np.full(32,10.)]),max_nfev=120,
                          ftol=2e-6,xtol=2e-6,gtol=2e-6)
    matrix = np.rint(fitted.x[:9].reshape(3,3)*1024).astype(int)
    knots = curve_from_params(fitted.x[9:],black,white)
    gamma = np.rint(np.interp(np.linspace(0,1,1024),np.linspace(0,1,33),knots)*1023).astype(int)
    return matrix, gamma, {"converged": bool(fitted.success), "evaluations": fitted.nfev,
                           "cost": float(fitted.cost), "message": fitted.message}


def write_profile(path, sha, order, matrix, gamma):
    # Small, strict, host-testable text format. Fits are digest-bound to a cube.
    path.write_text("SONY_PREVIEW_V1\n"+sha+"\n"+order+"\n"+
                    " ".join(map(str,matrix.ravel()))+"\n"+
                    " ".join(map(str,gamma))+"\n",encoding="ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cubes",nargs="+",type=Path)
    parser.add_argument("--out",required=True,type=Path)
    parser.add_argument("--image",type=Path)
    args = parser.parse_args(); args.out.mkdir(parents=True,exist_ok=True)
    reports = []
    for path in args.cubes:
        cube,lo,hi = load_cube(path)
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        validation = np.random.default_rng(330063).random((20000,3))
        target = tetra(cube,lo,hi,validation)
        report = {"source": str(path.resolve()),"sha256":sha,"grid":len(cube),
                  "hardware_validated":False,"note":"sRGB model hypotheses; hardware order/domain unconfirmed",
                  "identity_baseline":metrics(target,validation),"models":{}}
        candidates = []
        for order in ("matrix_gamma","gamma_matrix"):
            m,g,solver = fit(cube,lo,hi,order)
            modeled = apply(validation,m/1024.,g/1023.,order)
            profile = args.out/(path.stem+"_"+order+".PRV")
            write_profile(profile,sha,order,m,g)
            report["models"][order] = {"profile":profile.name,"matrix_q10":m.tolist(),
                                       "validation":metrics(target,modeled),"solver":solver}
            candidates.append((order,m,g))
        if args.image:
            from PIL import Image, ImageDraw
            image = Image.open(args.image).convert("RGB"); image.thumbnail((480,320))
            pixels = np.asarray(image).reshape(-1,3)/255.
            w,h = image.size
            canvas = Image.new("RGB",(w*4,h+30),(20,20,20)); draw=ImageDraw.Draw(canvas)
            rows = [("Input",pixels),("Full 33 cube",tetra(cube,lo,hi,pixels))]
            rows += [(o,apply(pixels,m/1024.,g/1023.,o)) for o,m,g in candidates]
            for i,(label,values) in enumerate(rows):
                canvas.paste(Image.fromarray(np.rint(values.reshape(h,w,3)*255).astype("uint8")),(i*w,30))
                draw.text((i*w+8,8),label,fill="white")
            canvas.save(args.out/(path.stem+"_MODELS.png"))
        reports.append(report)
        print(json.dumps(report,ensure_ascii=False),flush=True)
    (args.out/"FIT_REPORT.json").write_text(json.dumps(reports,indent=2,ensure_ascii=False)+"\n")


if __name__ == "__main__":
    main()
