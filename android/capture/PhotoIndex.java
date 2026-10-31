package org.hongen.sonylutprobe;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** DCF identity and bounded index queries; no file-number/URI arithmetic. */
final class PhotoIndex {
    static final class NotReady extends IOException {
        NotReady(String message){super(message);}
    }
    static final class Entry {
        final String uri;final long order;final int folder,file;
        Entry(String uri,long order,int folder,int file) throws IOException {
            // fcspoint/rsmpoint are mutable anchors, never saved-photo IDs.
            if(uri==null||!uri.matches("avindex://[A-Za-z0-9]+/[0-9A-Fa-f]{8}-default/[0-9A-Fa-f]{8}-[0-9A-Fa-f]{8}")
                    ||order<0||folder<100||folder>999||file<1||file>9999)
                throw new IOException("invalid Sony image index entry");
            this.uri=uri;this.order=order;this.folder=folder;this.file=file;
        }
    }
    interface Query {
        List<Entry> read(String selection,String order,int limit) throws IOException;
    }
    private final Query query;private final File dcim;
    PhotoIndex(Query query,File dcim){this.query=query;this.dcim=dcim.getAbsoluteFile();}
    Entry source(File source) throws IOException {
        File wanted=source.getAbsoluteFile(),dir=wanted.getParentFile();
        if(dir==null||!dcim.equals(dir.getParentFile())||!dir.getName().matches("[0-9]{3}[A-Z0-9_]{5}")
                ||!wanted.getName().matches("[A-Z0-9_]{4}[0-9]{4}\\.JPG"))
            throw new IOException("unsupported DCF source path: "+source);
        int folder=Integer.parseInt(dir.getName().substring(0,3));
        int file=Integer.parseInt(wanted.getName().substring(4,8));
        if(folder<100||file<1)throw new IOException("invalid DCF source number");
        List<Entry> rows=query.read("dcf_folder_number="+folder+" AND dcf_file_number="+file,null,2);
        if(rows.isEmpty())throw new NotReady("source not yet visible in Sony image index");
        if(rows.size()!=1)throw new IOException("source index returned "+rows.size()+" matches");
        Entry entry=rows.get(0);
        if(entry.folder!=folder||entry.file!=file)throw new IOException("source index selection mismatch");
        return entry;
    }
    long latestOrder() throws IOException {
        List<Entry> rows=query.read(null,"rec_order DESC",1);
        return rows.isEmpty()?0:rows.get(0).order;
    }
    Entry savedAfter(long before,String sourceUri) throws IOException {
        if(before<0)throw new IOException("invalid save watermark");
        List<Entry> rows=query.read("rec_order>"+before,"rec_order DESC",2);
        if(rows.isEmpty())throw new NotReady("saved photo not yet visible in Sony image index");
        if(rows.size()!=1)throw new IOException("Sony save produced "+rows.size()+" new index entries");
        Entry entry=rows.get(0);
        if(entry.order<=before||entry.uri.equals(sourceUri))throw new IOException("Sony save did not create a new photo");
        return entry;
    }
}
