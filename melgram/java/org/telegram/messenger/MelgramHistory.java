package org.telegram.messenger;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import org.telegram.tgnet.TLRPC;
import java.util.*;
import java.util.concurrent.*;
/** Local-only text history; excludes disappearing and secret-chat messages. */
public final class MelgramHistory {
  private static final ExecutorService queue=Executors.newSingleThreadExecutor();
  private static final Object lock=new Object();
  private static final java.util.concurrent.ConcurrentHashMap<String,Integer> badges=new java.util.concurrent.ConcurrentHashMap<>();
  private static String badgeKey(int a,long dialog,int mid){return a+":"+dialog+":"+mid;}
  private static int badgeState(int a,long dialog,int mid){
    String key=badgeKey(a,dialog,mid);
    Integer cached=badges.get(key);if(cached!=null)return cached;
    int result=0;
    synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return 0;
      Cursor c=d.rawQuery("SELECT deleted FROM messages WHERE account=? AND dialog=? AND mid=? LIMIT 1",new String[]{""+a,""+dialog,""+mid});
      try{if(c.moveToFirst()&&c.getInt(0)!=0)result=2;}finally{c.close();}
      if(result==0){
        c=d.rawQuery("SELECT 1 FROM events WHERE account=? AND dialog=? AND mid=? AND kind=1 LIMIT 1",new String[]{""+a,""+dialog,""+mid});
        try{if(c.moveToFirst())result=1;}finally{c.close();}
      }
    }catch(Exception e){FileLog.e(e);}}
    if(badges.size()>12000)badges.clear();
    badges.put(key,result);return result;
  }
  private static SQLiteOpenHelper helper;
  public static class Entry {
    public long dialog,time; public int mid,kind; public String before,after;
    Entry(Cursor c){dialog=c.getLong(0);mid=c.getInt(1);kind=c.getInt(2);before=c.getString(3);after=c.getString(4);time=c.getLong(5);}
  }
  private static SQLiteDatabase db(){
    Context ctx=ApplicationLoader.applicationContext;
    if(ctx==null)return null;
    if(helper==null)helper=new SQLiteOpenHelper(ctx,"melgram_history.db",null,2){
      public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE messages(account INTEGER,dialog INTEGER,mid INTEGER,text TEXT,edit_date INTEGER,deleted INTEGER DEFAULT 0,PRIMARY KEY(account,dialog,mid))");
        d.execSQL("CREATE TABLE events(account INTEGER,dialog INTEGER,mid INTEGER,kind INTEGER,before_text TEXT,after_text TEXT,time INTEGER)");
        d.execSQL("CREATE INDEX IF NOT EXISTS history_event_message ON events(account,dialog,mid,kind)");
      }
      public void onUpgrade(SQLiteDatabase d,int a,int b){if(a<2)d.execSQL("CREATE INDEX IF NOT EXISTS history_event_message ON events(account,dialog,mid,kind)");}
    };
    return helper.getWritableDatabase();
  }
  private static void event(SQLiteDatabase d,int a,long dialog,int id,int kind,String before,String after){
    ContentValues v=new ContentValues();v.put("account",a);v.put("dialog",dialog);v.put("mid",id);v.put("kind",kind);
    v.put("before_text",before);v.put("after_text",after);v.put("time",System.currentTimeMillis());d.insert("events",null,v);
    badges.put(badgeKey(a,dialog,id),kind==2?2:1);
  }
  private static void save(SQLiteDatabase d,int a,TLRPC.Message m){
    if(m==null||m.id<=0||m.ttl>0||m.noforwards||m.action!=null)return;
    long did=MessageObject.getDialogId(m);
    if(did==0||DialogObject.isEncryptedDialog(did))return;
    String text=m.message==null?"":m.message.toString();
    if(text.length()>16384)text=text.substring(0,16384);
    String[] args={""+a,""+did,""+m.id};
    Cursor c=d.rawQuery("SELECT text,edit_date,deleted FROM messages WHERE account=? AND dialog=? AND mid=?",args);
    String old=null;int oldEdit=0,deleted=0;
    try{if(c.moveToFirst()){old=c.getString(0);oldEdit=c.getInt(1);deleted=c.getInt(2);}}finally{c.close();}
    ContentValues v=new ContentValues();v.put("account",a);v.put("dialog",did);v.put("mid",m.id);v.put("text",text);v.put("edit_date",m.edit_date);
    if(old==null){v.put("deleted",0);d.insertWithOnConflict("messages",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    else if(deleted==0&&m.edit_date>=oldEdit&&!old.equals(text)){
      if(MelgramConfig.get(MelgramConfig.HISTORY_EDITS))event(d,a,did,m.id,1,old,text);
      d.update("messages",v,"account=? AND dialog=? AND mid=?",args);
    }
  }
  public static void capture(int account,TLRPC.Message message){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||message==null)return;
    captureBatch(account,java.util.Collections.singletonList(message));
  }
  public static void captureBatch(int account,List<TLRPC.Message> messages){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||messages==null)return;
    final ArrayList<TLRPC.Message> copy=new ArrayList<>(messages);
    queue.execute(()->{synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return;
      for(TLRPC.Message m:copy)save(d,account,m);
    }catch(Exception e){FileLog.e(e);}}});
  }
  public static void markDeleted(int account,long dialog,List<Integer> ids){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)||ids==null||dialog==0)return;
    ArrayList<Integer> copy=new ArrayList<>(ids);
    queue.execute(()->{synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return;
      for(int mid:copy)delete(d,account,dialog,mid);
    }catch(Exception e){FileLog.e(e);}}});
  }
  private static void delete(SQLiteDatabase d,int a,long dialog,int id){
    String[] args={""+a,""+dialog,""+id};
    Cursor c=d.rawQuery("SELECT text FROM messages WHERE account=? AND dialog=? AND mid=? AND deleted=0",args);
    String old=null;try{if(c.moveToFirst())old=c.getString(0);}finally{c.close();}
    if(old==null)return;
    ContentValues v=new ContentValues();v.put("deleted",1);
    d.update("messages",v,"account=? AND dialog=? AND mid=?",args);event(d,a,dialog,id,2,old,"");
  }
  public static void markDeletedThrough(int a,long dialog,int mid){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES))return;
    queue.execute(()->{synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return;
      Cursor c=d.rawQuery("SELECT mid FROM messages WHERE account=? AND dialog=? AND mid<=? AND deleted=0",new String[]{""+a,""+dialog,""+mid});
      ArrayList<Integer> ids=new ArrayList<>();try{while(c.moveToNext())ids.add(c.getInt(0));}finally{c.close();}
      for(int id:ids)delete(d,a,dialog,id);
    }catch(Exception e){FileLog.e(e);}}});
  }
  public static ArrayList<Entry> recent(int a,long dialog,int limit){
    ArrayList<Entry> out=new ArrayList<>();
    synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return out;
      Cursor c=d.rawQuery("SELECT dialog,mid,kind,before_text,after_text,time FROM events WHERE account=?"+(dialog==0?"":" AND dialog=?")+" ORDER BY rowid DESC LIMIT "+Math.min(500,Math.max(1,limit)),
        dialog==0?new String[]{""+a}:new String[]{""+a,""+dialog});
      try{while(c.moveToNext())out.add(new Entry(c));}finally{c.close();}
    }catch(Exception e){FileLog.e(e);}}return out;
  }
  public static void clear(int a){
    for(String key:new java.util.ArrayList<>(badges.keySet()))if(key.startsWith(a+":"))badges.remove(key);
    synchronized(lock){try{SQLiteDatabase d=db();if(d!=null){d.delete("messages","account=?",new String[]{""+a});d.delete("events","account=?",new String[]{""+a});}}catch(Exception e){FileLog.e(e);}}
  }
  /** True only for messages already recorded locally. */
  private static boolean exists(int account,long dialog,int mid,boolean onlyDeleted){
    synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return false;
      String condition=dialog==0?"account=? AND mid=?":"account=? AND dialog=? AND mid=?";
      String[] args=dialog==0?new String[]{""+account,""+mid}:new String[]{""+account,""+dialog,""+mid};
      Cursor c=d.rawQuery("SELECT deleted FROM messages WHERE "+condition+" LIMIT 1",args);
      try{return c.moveToFirst()&&(!onlyDeleted||c.getInt(0)!=0);}finally{c.close();}
    }catch(Exception e){FileLog.e(e);return false;}}
  }
  public static boolean wasDeleted(int a,long dialog,int mid){
    return MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)&&badgeState(a,dialog,mid)==2;
  }
  public static boolean hasEdits(int a,long dialog,int mid){
    return MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)&&badgeState(a,dialog,mid)==1;
  }
  public static ArrayList<Entry> forMessage(int a,long dialog,int mid){
    ArrayList<Entry> out=new ArrayList<>();
    synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return out;
      Cursor c=d.rawQuery("SELECT dialog,mid,kind,before_text,after_text,time FROM events WHERE account=? AND dialog=? AND mid=? ORDER BY rowid DESC LIMIT 100",
        new String[]{""+a,""+dialog,""+mid});
      try{while(c.moveToNext())out.add(new Entry(c));}finally{c.close();}
    }catch(Exception e){FileLog.e(e);}}
    return out;
  }
  /** Do not allow regular Telegram cache cleanup to remove a locally saved message. */
  public static void retainDeletedInStorage(int account,long dialog,ArrayList<Integer> ids){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
      ||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)||ids==null||ids.isEmpty())return;
    ArrayList<Integer> keep=new ArrayList<>();
    for(int mid:ids)if(exists(account,dialog,mid,false))keep.add(mid);
    if(!keep.isEmpty()){
      if(dialog!=0)markDeleted(account,dialog,keep);
      else for(Integer mid:keep)markDeletedUnknown(account,mid);
      ids.removeAll(keep);
    }
  }
  /** Omit locally retained messages from visual deletion events in the chat. */
  @SuppressWarnings("unchecked")
  public static void filterDeleteEvent(int account,Object[] args){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
       ||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)||args==null||args.length<3)return;
    if(Boolean.TRUE.equals(args[2]) || !(args[0] instanceof ArrayList) || !(args[1] instanceof Long))return;
    ArrayList<Integer> source=(ArrayList<Integer>)args[0];
    long channel=(Long)args[1];long dialog=channel>0?-channel:0;
    ArrayList<Integer> remaining=new ArrayList<>(source);
    ArrayList<Integer> retained=new ArrayList<>();
    for(Integer mid:source){
      if(mid!=null&&exists(account,dialog,mid,false)){remaining.remove(mid);retained.add(mid);}
    }
    if(!retained.isEmpty()){
      args[0]=remaining;
      if(dialog!=0)markDeleted(account,dialog,retained);
      else for(Integer mid:retained)markDeletedUnknown(account,mid);
    }
  }
  private static void markDeletedUnknown(int a,int mid){
    synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return;
      Cursor c=d.rawQuery("SELECT dialog FROM messages WHERE account=? AND mid=?",new String[]{""+a,""+mid});
      ArrayList<Long> dialogs=new ArrayList<>();
      try{while(c.moveToNext())dialogs.add(c.getLong(0));}finally{c.close();}
      for(long dialog:dialogs)markDeleted(a,dialog,java.util.Collections.singletonList(mid));
    }catch(Exception e){FileLog.e(e);}}
  }
  /** Display concise in-bubble marker; the actual message text stays intact. */
  public static CharSequence decorate(int account,TLRPC.Message message,CharSequence text){
    if(message==null||text==null||message.id<=0||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE))return text;
    long did=MessageObject.getDialogId(message);
    boolean deleted=wasDeleted(account,did,message.id);
    boolean edited=!deleted&&hasEdits(account,did,message.id);
    if(!deleted&&!edited)return text;
    String label=deleted?"🗑 Удалено\n":"✎ Изменено\n";
    if(text.toString().startsWith(label))return text;
    android.text.SpannableStringBuilder result=new android.text.SpannableStringBuilder(label);
    result.setSpan(new android.text.style.ForegroundColorSpan(deleted?0xffdb4545:0xff438ccf),0,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    result.append(text);
    return result;
  }
  private MelgramHistory(){}
}