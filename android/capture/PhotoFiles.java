package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;

/** Discover only new files of the expected camera format; never mutate DCIM. */
final class PhotoFiles {
    enum Format { JPEG(".JPG"), ARW(".ARW");
        final String suffix;Format(String suffix){this.suffix=suffix;}
    }
    private final File dcim;
    private final Format format;
    private final Set<String> known=new HashSet<String>();
    private String candidate,stored;
    private long length=-1,modified=-1;
    private CaptureGeometry geometry;
    synchronized void expect(CaptureGeometry value){geometry=value;}
    PhotoFiles(File sd) throws IOException {
        this(sd,Format.JPEG);
    }
    PhotoFiles(File sd,Format format) throws IOException {
        this.format=format;
        dcim=new File(sd,"DCIM").getCanonicalFile();
        if(!dcim.isDirectory())throw new IOException("DCIM directory unavailable");
        refresh();
    }
    private List<File> imageFiles() throws IOException {
        List<File> found=new ArrayList<File>();File[] dirs=dcim.listFiles();
        if(dirs==null)throw new IOException("cannot read DCIM");
        for(File dir:dirs)if(dir.isDirectory()) {
            File[] files=dir.listFiles();if(files==null)throw new IOException("cannot read "+dir.getName());
            for(File f:files)if(f.isFile() && f.getName().toUpperCase(Locale.US).endsWith(format.suffix)) {
                File real=f.getCanonicalFile();
                if(!real.getPath().startsWith(dcim.getPath()+File.separator))throw new IOException("photo outside DCIM");
                found.add(real);
            }
        }
        return found;
    }
    synchronized void refresh() throws IOException {
        known.clear();for(File f:imageFiles())known.add(f.getPath());resetCandidate();
    }
    synchronized void resetCandidate(){candidate=null;length=-1;modified=-1;}
    synchronized File pollReady() throws IOException {
        return pollReady(false);
    }
    synchronized File pollReady(boolean awaitStoreNotification) throws IOException {
        if(awaitStoreNotification)return pollStored();
        return pollFresh(false);
    }
    synchronized File pollRecovery()throws IOException{return pollFresh(true);}
    private File pollFresh(boolean requireComplete)throws IOException{
        List<File> fresh=new ArrayList<File>();for(File f:imageFiles())if(!known.contains(f.getPath()))fresh.add(f);
        if(fresh.size()>1)throw new IOException("more than one new "+format+"; use single-shot mode");
        if(fresh.isEmpty()){resetCandidate();return null;}
        File f=fresh.get(0);long size=f.length(),time=f.lastModified();
        boolean stable=f.getPath().equals(candidate)&&size==length&&time==modified;
        candidate=f.getPath();length=size;modified=time;
        if(!stable || size<4)return null;
        if(!requireComplete&&f.getPath().equals(stored))return f;
        return (requireComplete?completeStructure(f):complete(f))?f:null;
    }
    private File pollStored() throws IOException {
        // StoreImageComplete names the exact new file. Do not enumerate DCIM
        // while the camera's filesystem service is busy finishing the shot.
        if(stored==null||known.contains(stored))return null;
        File file=new File(stored);if(!file.isFile())return null;
        // This path is supplied only by Sony's completed-store event. The
        // unnotified/recovery path still checks stability and JPEG markers.
        return file.length()>=4?file:null;
    }
    synchronized void confirmStored(String directory,String name) throws IOException {
        File file=storedFile(directory,name);
        if(!known.contains(file.getPath()))stored=file.getPath();
    }
    synchronized boolean knownStore(String directory,String name) throws IOException {
        return known.contains(storedFile(directory,name).getPath());
    }
    private File storedFile(String directory,String name) throws IOException {
        if(directory==null||name==null||!directory.matches("DCIM/[0-9]{3}[A-Z0-9_]{5}/?")
                ||!name.matches("[A-Z0-9_]{1,8}(\\"+format.suffix+")?"))throw new IOException("invalid Sony store notification path for "+format);
        File file=new File(new File(dcim.getParentFile(),directory),name.endsWith(format.suffix)?name:name+format.suffix).getCanonicalFile();
        if(!file.getPath().startsWith(dcim.getPath()+File.separator))throw new IOException("store notification outside DCIM");
        return file;
    }
    boolean complete(File file) throws IOException {
        return expectedHeader(file)&&completeStructure(file);
    }
    boolean completeStructure(File file)throws IOException{return format==Format.JPEG?completeJpeg(file):ArwFile.completeA6300(file);}
    boolean expectedHeader(File file)throws IOException{return format!=Format.JPEG||geometry==null||jpegHeader(file,geometry.width,geometry.height);}
    // Fast structural check after Sony's completed-store event. Full entropy/EOI
    // validation can follow first-frame display without stalling the pipeline.
    static boolean jpegHeader(File file,int width,int height)throws IOException{
        HeaderReader in=new HeaderReader(file);try{
            long size=in.length();if(size<4||in.readUnsignedShort()!=0xffd8)return false;
            while(in.getFilePointer()<Math.min(size,1024*1024)){
                if(in.read()!=255)return false;int marker;do{marker=in.read();}while(marker==255);
                if(marker<0||marker==0||marker==216||marker==217||marker==218)return false;
                int bytes=in.readUnsignedShort();if(bytes<2||in.getFilePointer()+bytes-2>size)return false;
                if(marker==192||marker==194){
                    if(bytes!=17)return false;
                    return in.read()==8&&in.readUnsignedShort()==height&&in.readUnsignedShort()==width&&in.read()==3;
                }
                in.seek(in.getFilePointer()+bytes-2);
            }
            return false;
        }catch(EOFException e){return false;}finally{in.close();}
    }
    // Sony routes SD operations through its filesystem service. Reading each
    // marker byte separately adds many remote calls to every saved-photo check.
    // Cache a small window and skip APP/EXIF payloads by logical position, so
    // thumbnails are not read just to reach the main image's SOF marker.
    private static final class HeaderReader {
        private final RandomAccessFile source;private final long size;
        private final byte[] window=new byte[4096];
        private long position,start=-1;private int count;
        HeaderReader(File file)throws IOException{
            source=new RandomAccessFile(file,"r");
            try{size=source.length();}catch(IOException e){source.close();throw e;}
        }
        long length(){return size;}
        long getFilePointer(){return position;}
        void seek(long next)throws IOException{
            if(next<0||next>size)throw new EOFException("JPEG header seek outside file");
            position=next;
        }
        int read()throws IOException{
            if(position>=size)return -1;
            if(start<0||position<start||position>=start+count){
                source.seek(position);start=position;
                count=source.read(window,0,(int)Math.min(window.length,size-position));
                if(count<=0)throw new EOFException("JPEG header changed during read");
            }
            return window[(int)(position++-start)]&255;
        }
        int readUnsignedShort()throws IOException{
            int hi=read(),lo=read();if(hi<0||lo<0)throw new EOFException();return hi<<8|lo;
        }
        void close()throws IOException{source.close();}
    }
    // Sony pads JPEG files after EOI. Walk markers, skipping APP/EXIF thumbnails,
    // and recognize EOI only after an image scan, not at the physical file end.
    static boolean completeJpeg(File file) throws IOException {
        JpegStream in=new JpegStream(file);
        try {
            if(in.read()!=255||in.read()!=216)return false;
            boolean scan=false;int marker=-1;
            while(true) {
                if(marker<0){if(in.read()!=255)return false;do{marker=in.read();}while(marker==255);}
                if(marker<0)return false;
                if(marker==217)return scan;
                if(marker==0||marker==216||(marker>=208&&marker<=215))return false;
                int hi=in.read(),lo=in.read();if(hi<0||lo<0)return false;
                int size=(hi<<8|lo)-2;if(size<0)return false;
                while(size>0){long n=in.skip(size);if(n>0)size-=n;else {if(in.read()<0)return false;size--;}}
                if(marker==218) {
                    scan=true;
                    marker=in.scanMarker();if(marker<0)return false;
                } else marker=-1;
            }
        }finally{in.close();}
    }
    // Scan entropy bytes in a local array, avoiding millions of synchronized
    // BufferedInputStream.read() calls on the camera's Dalvik runtime.
    private static final class JpegStream {
        private final InputStream source;private final byte[] buffer=new byte[65536];
        private int position,limit;
        JpegStream(File file) throws IOException{source=new FileInputStream(file);}
        private boolean fill() throws IOException{position=0;limit=source.read(buffer);return limit>0;}
        int read() throws IOException{if(position>=limit&&!fill())return -1;return buffer[position++]&255;}
        long skip(long n) throws IOException {
            int cached=(int)Math.min(n,Math.max(0,limit-position));position+=cached;
            return cached>0?cached:source.skip(n);
        }
        int scanMarker() throws IOException {
            while(true) {
                while(position<limit&&buffer[position]!=(byte)255)position++;
                if(position>=limit){if(!fill())return -1;continue;}
                position++;int marker;do{marker=read();}while(marker==255);
                if(marker==0||(marker>=208&&marker<=215))continue;
                return marker;
            }
        }
        void close() throws IOException{source.close();}
    }
    void recover(File sd,String relative) throws IOException {
        File f=new File(sd,relative).getCanonicalFile();
        if(!f.getPath().startsWith(dcim.getPath()+File.separator)||!f.isFile()||!f.getName().toUpperCase(Locale.US).endsWith(format.suffix))throw new IOException("invalid recovery "+format);
        known.remove(f.getPath());resetCandidate();
    }
    String describe() throws IOException {
        List<File> files=imageFiles();Collections.sort(files,new Comparator<File>(){public int compare(File a,File b){return a.lastModified()<b.lastModified()?1:a.lastModified()>b.lastModified()?-1:b.getPath().compareTo(a.getPath());}});
        StringBuilder text=new StringBuilder("count="+files.size());
        for(int i=0;i<Math.min(8,files.size());i++) {
            File f=files.get(i);text.append("\n").append(f.getPath()).append(" bytes=").append(f.length()).append(" mtime=").append(f.lastModified());
            if(f.length()>=4){RandomAccessFile in=new RandomAccessFile(f,"r");try{int head=in.readUnsignedShort();in.seek(f.length()-2);int tail=in.readUnsignedShort();text.append(" head=").append(Integer.toHexString(head)).append(" tail=").append(Integer.toHexString(tail));}finally{in.close();}}
        }
        return text.toString();
    }
    synchronized void accepted(File file){known.add(file.getPath());if(file.getPath().equals(stored))stored=null;resetCandidate();}
}
