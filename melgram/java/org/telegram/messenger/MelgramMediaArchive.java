package org.telegram.messenger;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Private, opt-in archive of files already present in Telegram's local cache.
 * No downloads or network requests are initiated and no file exceeds 32 MiB.
 */
public final class MelgramMediaArchive {
    private static final long MAX_FILE = 32L * 1024 * 1024;
    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private MelgramMediaArchive() {}

    private static File root() {
        Context context=ApplicationLoader.applicationContext;
        if(context==null)return null;
        File folder=new File(context.getFilesDir(),"melgram_saved_media");
        if(!folder.exists()&&!folder.mkdirs())return null;
        return folder;
    }

    private static String extension(File source) {
        String filename=source.getName().toLowerCase(java.util.Locale.ROOT);
        int dot=filename.lastIndexOf('.');
        if(dot>=0&&dot+1<filename.length()) {
            String ext=filename.substring(dot+1);
            if(ext.matches("jpg|jpeg|png|webp|gif|mp4|mkv|webm|ogg|opus|mp3|m4a|pdf|txt|zip|tgs"))return ext;
        }
        return "bin";
    }

    private static String contentHash(File file) throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(FileInputStream in=new FileInputStream(file)){
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        StringBuilder hex=new StringBuilder(64);
        for(byte value:digest.digest()){
            int n=value&255;
            hex.append(Character.forDigit(n>>>4,16));
            hex.append(Character.forDigit(n&15,16));
        }
        return hex.toString();
    }

    public static String persist(int account,long dialog,int messageId,String sourcePath) {
        if(sourcePath==null||!MelgramConfig.get(MelgramConfig.HISTORY_MEDIA))return null;
        File folder=root();
        if(folder==null)return null;
        File source=new File(sourcePath);
        long length=source.length();
        if(!source.isFile()||length<=0||length>MAX_FILE)return null;
        try {
            // Version by content hash, not just message ID: old media revisions
            // never get overwritten by a new image or a same-size edit.
            String filename=account+"_"+dialog+"_"+messageId+"_"+
                contentHash(source)+"."+extension(source);
            File target=new File(folder,filename);
            if(target.isFile()&&target.length()==length)return target.getAbsolutePath();

            File[] children=folder.listFiles();
            long total=0;
            if(children!=null)for(File child:children)if(child.isFile())total+=child.length();
            if(total+length>MAX_TOTAL)return null;
            File temp=new File(folder,filename+".part");
            try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temp)){
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.getFD().sync();
            } catch(Exception e) {
                FileLog.e(e);temp.delete();return null;
            }
            if(temp.length()!=length){temp.delete();return null;}
            if(!temp.renameTo(target)){temp.delete();return null;}
            return target.getAbsolutePath();
        } catch(Exception e){FileLog.e(e);return null;}
    }

    /** Only delete files within the private archive, never external paths. */
    public static void delete(String path){
        if(path==null)return;
        File folder=root();if(folder==null)return;
        try{
            File f=new File(path).getCanonicalFile();
            if(f.getParentFile().equals(folder.getCanonicalFile()))f.delete();
        }catch(Exception e){FileLog.e(e);}
    }

    public static String mimeType(String path) {
        if(path==null)return "application/octet-stream";
        String ext=android.webkit.MimeTypeMap.getFileExtensionFromUrl(path);
        String type=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.ROOT));
        return type==null?"application/octet-stream":type;
    }
}
