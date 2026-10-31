#!/usr/bin/env python3
"""Native solver checks with analytic LUTs, independent quantized evaluation.
Usage: python3 scripts/test_preview_fit.py [solver executable]
"""
import hashlib,json,pathlib,subprocess,sys,tempfile
import numpy as np
solver=pathlib.Path(sys.argv[1] if len(sys.argv)>1 else 'out/preview-fit-host').resolve()
root=pathlib.Path(tempfile.mkdtemp(prefix='sony-preview-fit-'))
cases={'identity':lambda x:x,'gamma':lambda x:x**.65,
       'monochrome':lambda x:np.repeat(np.dot(x,[.2126,.7152,.0722]),3),
       'swap':lambda x:x[[2,1,0]],'constant':lambda x:np.array([.2,.2,.2]),
       'domain':lambda x:x,'small':lambda x:x,'large':lambda x:x}
for name,fn in cases.items():
    cube=root/(name+'.cube');n=2 if name=='small' else 65 if name=='large' else 33
    cube.write_text('LUT_3D_SIZE '+str(n)+'\n'+('DOMAIN_MAX 2 2 2\n' if name=='domain' else '')+''.join(' '.join(map(str,fn(np.array([r,g,b])/(n-1))))+'\n' for b in range(n) for g in range(n) for r in range(n)))
    sha=hashlib.sha256(cube.read_bytes()).hexdigest();profile=root/(name+'.PRV')
    p=subprocess.run([str(solver),str(cube),sha,str(profile)],capture_output=True,text=True,check=True,timeout=30)
    report=json.loads(p.stdout);w=profile.read_text().split();assert w[:4]==['SONY_PREVIEW_V2',sha,'matrix_gamma','1']
    matrix=np.array(w[8:17],dtype=int).reshape(3,3)/1024;gamma=np.array(w[17:],dtype=int)/1023
    assert len(gamma)==1024 and np.all(np.diff(gamma)>=0) and gamma.min()>=0 and gamma.max()<=1
    assert np.max(np.abs(matrix))<=2
    rng=np.random.default_rng(987);points=rng.integers(0,256,(10000,3))/255.
    expected=np.array([fn(x/2 if name=='domain' else x) for x in points]);actual=np.interp(np.clip(points@matrix.T,0,1),np.linspace(0,1,1024),gamma)
    error=np.abs(expected-actual).mean()*255
    assert error<1.2,(name,error,report)
    assert np.isfinite(list(report.values())).all()
    print(name,'independent MAE',round(error,4),'fit_ms',report['fit_ms'])
bad=root/'bad.cube';bad.write_text('LUT_3D_SIZE 33\nNaN 0 0\n')
p=subprocess.run([str(solver),str(bad),'a'*64,str(root/'bad.PRV')],capture_output=True)
assert p.returncode!=0 and not (root/'bad.PRV').exists()
print('PASS native fit analytic identities, curve, channel mixing, monotonicity, bounds, independent samples and malformed input')
