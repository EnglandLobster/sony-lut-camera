#!/usr/bin/env python3
"""Numerical contracts for the offline preview fitter (no camera access)."""
import tempfile
import unittest
from pathlib import Path
import numpy as np
from fit_native_preview import load_cube, tetra, apply, curve_from_params, lab


class PreviewFitTest(unittest.TestCase):
    def test_tetra_identity_and_channels(self):
        b,g,r=np.meshgrid(np.linspace(0,1,3),np.linspace(0,1,3),np.linspace(0,1,3),indexing="ij")
        cube=np.stack([r,g,b],axis=-1)
        points=np.vstack([np.random.default_rng(7).random((1000,3)),np.eye(3),[[0,0,0],[1,1,1]]])
        np.testing.assert_allclose(tetra(cube,np.zeros(3),np.ones(3),points),points,atol=1e-14)
        swapped=cube[..., [1,2,0]]
        np.testing.assert_allclose(tetra(swapped,np.zeros(3),np.ones(3),points),points[:,[1,2,0]],atol=1e-14)

    def test_tetra_not_trilinear(self):
        cube=np.zeros((2,2,2,3));cube[1,1,1]=1
        np.testing.assert_allclose(tetra(cube,np.zeros(3),np.ones(3),np.array([[.2,.4,.6]])),[[.2]*3])

    def test_domains_and_parser(self):
        text="LUT_3D_SIZE 2\nDOMAIN_MIN -1 -1 -1\nDOMAIN_MAX 1 1 1\n"+"\n".join(
            f"{r} {g} {b}" for b in [0,1] for g in [0,1] for r in [0,1])
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"test.cube";p.write_text(text)
            cube,lo,hi=load_cube(p)
            np.testing.assert_allclose(tetra(cube,lo,hi,np.array([[0.,0.,0.]])),[[.5]*3])
            for bad in [text+"\n0 0 0",text.replace("DOMAIN_MAX 1 1 1","DOMAIN_MAX -1 1 1"),text.replace("0 0 0","nan 0 0")]:
                p.write_text(bad)
                with self.assertRaises(ValueError):load_cube(p)

    def test_monotone_and_order_distinction(self):
        g=curve_from_params(np.random.default_rng(2).normal(0,3,32),.03,.96)
        self.assertTrue(np.all(np.diff(g)>0));self.assertAlmostEqual(g[0],.03);self.assertAlmostEqual(g[-1],.96)
        rgb=np.array([[.1,.8,.4]]);m=np.array([[.7,.3,0],[0,1,0],[0,0,1]])
        g=np.linspace(0,1,1024)**2
        self.assertGreater(np.max(np.abs(apply(rgb,m,g,"matrix_gamma")-apply(rgb,m,g,"gamma_matrix"))),.05)
        np.testing.assert_allclose(lab(np.array([[1.,1.,1.]])),[[100,0,0]],atol=.0001)


if __name__=="__main__":unittest.main()
