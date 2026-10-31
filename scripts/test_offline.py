#!/usr/bin/env python3
"""Numerical contracts and real JPEG failures. Never communicates with a camera."""
import hashlib,json,os,pathlib,struct,subprocess,sys,resource,signal
from PIL import Image
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'out'/'tests';OUT.mkdir(parents=True,exist_ok=True)
def cube(name,n,fn,domain=False):
    p=OUT/name
    with p.open('w') as f:
        f.write(f'LUT_3D_SIZE {n}\n')
        if domain:f.write('DOMAIN_MIN 0.1 -0.2 0.2\nDOMAIN_MAX 0.9 1.2 0.8\n')
        for b in range(n):
            for g in range(n):
                for r in range(n):f.write(' '.join(f'{v:.10f}' for v in fn(r/(n-1),g/(n-1),b/(n-1)))+'\n')
    return p
identity=cube('ID33.CUB',33,lambda r,g,b:(r,g,b))
color=cube('COLOR17.CUB',17,lambda r,g,b:(r*r+g*.08,g*.9+b*.05,b*.8+r*.1))
domain=cube('DOMAIN.CUB',17,lambda r,g,b:(r*1.3-.15,g,b*1.2-.1),True)
bad=OUT/'BAD.CUB';bad.write_text('LUT_3D_SIZE 33\n0 0 0\n')
# Self-contained nonlinear 64-cube coverage; no user/third-party film LUT required.
stock=cube('COLOR64.CUB',64,lambda r,g,b:(r*r+g*.08,g*.9+b*.05,b*.8+r*.1))
def run(args,ok=True):
    p=subprocess.run([str(x) for x in args],capture_output=True,text=True,timeout=40)
    if (p.returncode==0)!=ok:raise AssertionError((args,p.stdout,p.stderr))
    return p.stdout.strip()
print(run([ROOT/'out/core-tests',identity,bad,color,domain,stock]))
cli=pathlib.Path(os.environ.get('SONY_LUT_CLI',ROOT/'out/sony-lut-host'))
results=[]
for w,h in [(641,479),(3000,2000),(6000,4000)]:
    src=OUT/f'IN{w}.JPG'
    # Bands include skin-like colours, neutral ramps and hard saturated edges.
    im=Image.new('RGB',(w,h));row=Image.new('RGB',(w,1))
    row.putdata([(x*255//max(1,w-1),(x*7)%256,(x*13)%256) for x in range(w)])
    for y in range(h):im.paste(row,(0,y))
    ex=Image.Exif();ex[271]='SONY';ex[272]='ILCE-6300';ex[274]=6
    ex[34665]={40962:w,40963:h,34855:100}
    im.save(src,quality=96,exif=ex);im.close()
    before=hashlib.sha256(src.read_bytes()).hexdigest()
    def process(label,lut,workers=1,scale=1,batch=64):
        dest=OUT/f'{label}{w}.JPG';dest.unlink(missing_ok=True)
        s=json.loads(run([cli,lut,src,dest,workers,scale,92,batch]))
        results.append(dict(case=label,input=f'{w}x{h}',**s))
        with Image.open(dest) as img:
            assert img.size==((w+scale-1)//scale,(h+scale-1)//scale)
            e=img.getexif();assert e[272]=='ILCE-6300' and e[274]==6
            x=e.get_ifd(34665);assert x[40962]==img.width and x[40963]==img.height
        return dest
    base=process('CODEC','--codec');ident=process('ID',identity)
    assert base.read_bytes()==ident.read_bytes(),'identity changed encoded pixels'
    outputs=[process(f'L{n}',color,n,batch=13 if n==1 else 64 if n==2 else 128) for n in (1,2,4)]
    assert len({hashlib.sha256(p.read_bytes()).digest() for p in outputs})==1,'workers/batches changed JPEG'
    for threads,batch in [(1,13),(2,64),(4,128)]:
        piped=OUT/f'PIPE{threads}_{w}.JPG';piped.unlink(missing_ok=True)
        stats=json.loads(run([cli,color,src,piped,threads,1,92,batch,1]))
        assert stats['pipeline'] and stats['row_buffer_bytes']==w*3*batch*4
        assert piped.read_bytes()==outputs[0].read_bytes(),'pipeline changed JPEG'
    process('SCALE2',color,2,2)
    assert before==hashlib.sha256(src.read_bytes()).hexdigest(),'source overwritten'
    run([cli,color,src,outputs[0]],ok=False)
# Reject truncated input and leave no partial JPEG.
broken=OUT/'BROKEN.JPG';data=(OUT/'IN641.JPG').read_bytes()
dest=OUT/'FAILED.JPG';dest.unlink(missing_ok=True)
for end in (200,len(data)*4//5):
    broken.write_bytes(data[:end])
    run([cli,color,broken,dest],ok=False);assert not dest.exists()
    run([cli,color,broken,dest,2,1,92,13,1],ok=False);assert not dest.exists()
# Force an encoder write failure while other stages still own live buffers.
def small_output_limit():
    signal.signal(signal.SIGXFSZ,signal.SIG_IGN)
    resource.setrlimit(resource.RLIMIT_FSIZE,(16384,16384))
limited=subprocess.run([str(x) for x in [cli,color,OUT/'IN3000.JPG',dest,2,1,92,13,1]],
    capture_output=True,text=True,timeout=40,preexec_fn=small_output_limit)
assert limited.returncode!=0 and 'JPEG encode:' in limited.stderr,(limited.returncode,limited.stderr)
assert not dest.exists(),'failed pipeline output retained'
for text in ['LUT_1D_SIZE 2\n0 0 0\n1 1 1\n','LUT_3D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\n'+('0 0 0\n'*8),
             'LUT_3D_SIZE 66\n','LUT_3D_SIZE 2\n'+('nan 0 0\n'*8),
             'LUT_3D_SIZE 2\n'+('17 0 0\n'*8)]:
    invalid=OUT/'INVALID.CUB';invalid.write_text(text)
    run([cli,invalid,OUT/'IN641.JPG',dest],ok=False);assert not dest.exists()
print(run([ROOT/'out/preview-tests',identity,color,OUT/'IN641.JPG',OUT/'IN3000.JPG']))
with Image.open(OUT/'IN641.JPG') as im:
    progressive=OUT/'PROGRESS.JPG';im.save(progressive,progressive=True)
run([cli,color,progressive,dest],ok=False);assert not dest.exists()
# IFD0 points to an IFD1 thumbnail directory. Copying its colour would be stale.
thumb=OUT/'THUMB.JPG';thumbnail_exif=b'Exif\0\0II'+struct.pack('<HIHIH',42,8,0,14,0)+struct.pack('<I',0)
with Image.open(OUT/'IN641.JPG') as im:im.save(thumb,exif=thumbnail_exif)
run([cli,color,thumb,dest])
with Image.open(dest) as im:
    ex=im.info['exif'];offset=struct.unpack_from('<I',ex,10)[0];n=struct.unpack_from('<H',ex,6+offset)[0]
    assert struct.unpack_from('<I',ex,6+offset+2+n*12)[0]==0,'stale thumbnail IFD retained'
dest.unlink()
timing_file='jpeg-timings-sanitize.json' if 'sanitize' in cli.name else 'jpeg-timings.json'
(OUT/timing_file).write_text(json.dumps(results,indent=2))
print('PASS JPEG: odd/6MP/24MP dimensions, identity byte equality, 1/2/4 threads, EXIF and thumbnail detach, source preservation, no overwrite, corrupt input cleanup including pipeline cancellation, pipeline byte equality, progressive and invalid LUT rejection')
