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
  // Badges are looked up off the UI thread and cached (including absent badges).
  // A cell can be recycled while a query is running: its identity is checked
  // before notifying RecyclerView to perform a normal, grouped-message-safe rebind.
  private static final java.util.concurrent.ConcurrentHashMap<String,
      java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<android.view.View>>> badgeObservers =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static int readBadgeFromDb(int a,long dialog,int mid) {
    int state=0;
    synchronized(lock) {
      try {
        SQLiteDatabase database=db();
        if(database==null)return 0;
        String[] args={""+a,""+dialog,""+mid};
        Cursor c=database.rawQuery("SELECT deleted FROM messages WHERE account=? AND dialog=? AND mid=? LIMIT 1",args);
        try { if(c.moveToFirst()&&c.getInt(0)!=0)state|=2; } finally { c.close(); }
        c=database.rawQuery("SELECT 1 FROM events WHERE account=? AND dialog=? AND mid=? AND kind=1 LIMIT 1",args);
        try { if(c.moveToFirst())state|=1; } finally { c.close(); }
      } catch(Exception e) { FileLog.e(e); }
    }
    return state;
  }

  private static void rebindBadgeObserver(android.view.View view,int account,long dialog,int mid) {
    if(!(view instanceof org.telegram.ui.Cells.ChatMessageCell))return;
    org.telegram.ui.Cells.ChatMessageCell cell=(org.telegram.ui.Cells.ChatMessageCell)view;
    MessageObject m=cell.getPrimaryMessageObject();
    if(m==null||m.currentAccount!=account||m.getDialogId()!=dialog||m.getId()!=mid)return;
    android.view.ViewParent parent=cell.getParent();
    if(parent instanceof androidx.recyclerview.widget.RecyclerView) {
      androidx.recyclerview.widget.RecyclerView list=(androidx.recyclerview.widget.RecyclerView)parent;
      list.post(()->{
        if(cell.getParent()!=list)return;
        MessageObject current=cell.getPrimaryMessageObject();
        if(current==null||current.currentAccount!=account||current.getDialogId()!=dialog||current.getId()!=mid)return;
        androidx.recyclerview.widget.RecyclerView.Adapter adapter=list.getAdapter();
        int position=list.getChildAdapterPosition(cell);
        if(adapter!=null&&position!=androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
          adapter.notifyItemChanged(position);
        }
      });
    } else {
      cell.requestLayout();
      cell.invalidate();
    }
  }

  public static int statusForView(int a,long dialog,int mid,android.view.View view) {
    if(mid<=0||dialog==0)return 0;
    String key=badgeKey(a,dialog,mid);
    Integer state=badges.get(key);
    if(state!=null&&state>=0)return state;
    if(view!=null) {
      badgeObservers.compute(key,(k,observers)->{
        if(observers==null)observers=new java.util.concurrent.CopyOnWriteArrayList<>();
        if(observers.size()<32)observers.add(new java.lang.ref.WeakReference<>(view));
        return observers;
      });
    }
    if(badges.putIfAbsent(key,-1)==null) {
      queue.execute(()->{
        int fetched=readBadgeFromDb(a,dialog,mid);
        // An edit/delete event may have been recorded while the read was pending.
        badges.compute(key,(k,current)->current==null||current<0?fetched:current|fetched);
        java.util.List<java.lang.ref.WeakReference<android.view.View>> observers=badgeObservers.remove(key);
        if(observers!=null)AndroidUtilities.runOnUIThread(()->{
          for(java.lang.ref.WeakReference<android.view.View> ref:observers) {
            android.view.View v=ref.get();
            if(v!=null)rebindBadgeObserver(v,a,dialog,mid);
          }
        });
      });
    }
    if(badges.size()>12000) { badges.clear(); badgeObservers.clear(); }
    return 0;
  }
  private static int badgeState(int a,long dialog,int mid) {
    return statusForView(a,dialog,mid,null);
  }
  private static SQLiteOpenHelper helper;
  public static class Entry {
    public long dialog,time; public int mid,kind; public String before,after;
    Entry(Cursor c){dialog=c.getLong(0);mid=c.getInt(1);kind=c.getInt(2);before=c.getString(3);after=c.getString(4);time=c.getLong(5);}
  }
  private static SQLiteDatabase db(){
    Context ctx=ApplicationLoader.applicationContext;
    if(ctx==null)return null;
    if(helper==null)helper=new SQLiteOpenHelper(ctx,"melgram_history.db",null,4){
      public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE messages(account INTEGER,dialog INTEGER,mid INTEGER,text TEXT,edit_date INTEGER,deleted INTEGER DEFAULT 0,msg_date INTEGER DEFAULT 0,sender_id INTEGER DEFAULT 0,is_out INTEGER DEFAULT 0,media_path TEXT,media_bytes INTEGER DEFAULT 0,PRIMARY KEY(account,dialog,mid))");
        d.execSQL("CREATE INDEX IF NOT EXISTS history_deleted_range ON messages(account,dialog,deleted,mid)");
        d.execSQL("CREATE TABLE events(account INTEGER,dialog INTEGER,mid INTEGER,kind INTEGER,before_text TEXT,after_text TEXT,time INTEGER)");
        d.execSQL("CREATE INDEX IF NOT EXISTS history_event_message ON events(account,dialog,mid,kind)");
      }
      public void onUpgrade(SQLiteDatabase d,int a,int b){
        if(a<2)d.execSQL("CREATE INDEX IF NOT EXISTS history_event_message ON events(account,dialog,mid,kind)");
        if(a<3){
          d.execSQL("ALTER TABLE messages ADD COLUMN msg_date INTEGER DEFAULT 0");
          d.execSQL("ALTER TABLE messages ADD COLUMN sender_id INTEGER DEFAULT 0");
          d.execSQL("ALTER TABLE messages ADD COLUMN is_out INTEGER DEFAULT 0");
          d.execSQL("CREATE INDEX IF NOT EXISTS history_deleted_range ON messages(account,dialog,deleted,mid)");
        }
        if(a<4){
          d.execSQL("ALTER TABLE messages ADD COLUMN media_path TEXT");
          d.execSQL("ALTER TABLE messages ADD COLUMN media_bytes INTEGER DEFAULT 0");
        }
      }
    };
    return helper.getWritableDatabase();
  }
  private static void event(SQLiteDatabase d,int a,long dialog,int id,int kind,String before,String after){
    event(d,a,dialog,id,kind,before,after,System.currentTimeMillis());
  }
  private static void event(SQLiteDatabase d,int a,long dialog,int id,int kind,String before,String after,long occurredAt){
    ContentValues v=new ContentValues();v.put("account",a);v.put("dialog",dialog);v.put("mid",id);v.put("kind",kind);
    v.put("before_text",before);v.put("after_text",after);v.put("time",occurredAt);d.insert("events",null,v);
    badges.compute(badgeKey(a,dialog,id),(key,oldValue)->(oldValue==null||oldValue<0?0:oldValue)|(kind==2?2:1));
  }
  // Snapshot the message before Telegram changes its mutable TL objects.
  private static final class Snapshot {
    final int account,id,date,editDate;
    final long dialog,sender;
    final boolean outgoing;
    final String text,mediaSource;
    String savedMedia;
    Snapshot(int account,int id,int date,int editDate,long dialog,long sender,boolean outgoing,String text,String mediaSource){
      this.account=account;this.id=id;this.date=date;this.editDate=editDate;
      this.dialog=dialog;this.sender=sender;this.outgoing=outgoing;
      this.text=text;this.mediaSource=mediaSource;
    }
  }
  private static Snapshot snapshot(int a,TLRPC.Message m){
    if(m==null||m.id<=0||m.ttl>0||m.noforwards||m.action!=null)return null;
    long did=MessageObject.getDialogId(m);
    if(did==0||DialogObject.isEncryptedDialog(did)||!MelgramConfig.historyDialogEnabled(a,did))return null;
    if(did>0&&!MelgramConfig.get(MelgramConfig.HISTORY_BOTS)){
      TLRPC.User user=MessagesController.getInstance(a).getUser(did);
      if(user!=null&&user.bot)return null;
    }
    String value=m.message==null?"":m.message.toString();
    if(value.isEmpty()){
      if(m.media==null)return null;
      value="[Медиа без подписи]";
    }
    if(value.length()>16384)value=value.substring(0,16384);
    long sender=0L;
    if(m.from_id!=null){
      if(m.from_id.user_id!=0)sender=m.from_id.user_id;
      else if(m.from_id.chat_id!=0)sender=-m.from_id.chat_id;
      else if(m.from_id.channel_id!=0)sender=-m.from_id.channel_id;
    }
    String mediaSource=null;
    if(m.media!=null&&MelgramConfig.get(MelgramConfig.HISTORY_MEDIA)){
      try {
        java.io.File cached=FileLoader.getInstance(a).getPathToMessage(m);
        if(cached!=null&&cached.isFile()&&cached.length()>0)mediaSource=cached.getAbsolutePath();
      }catch(Throwable e){FileLog.e(e);}
    }
    return new Snapshot(a,m.id,m.date,m.edit_date,did,sender,m.out,value,mediaSource);
  }
  private static void save(SQLiteDatabase d,Snapshot snap){
    String[] args={""+snap.account,""+snap.dialog,""+snap.id};
    Cursor c=d.rawQuery("SELECT text,edit_date,deleted FROM messages WHERE account=? AND dialog=? AND mid=?",args);
    String old=null;int oldEdit=0,deleted=0;
    try{if(c.moveToFirst()){old=c.getString(0);oldEdit=c.getInt(1);deleted=c.getInt(2);}}finally{c.close();}
    ContentValues v=new ContentValues();
    v.put("account",snap.account);v.put("dialog",snap.dialog);v.put("mid",snap.id);
    v.put("text",snap.text);v.put("edit_date",snap.editDate);
    v.put("msg_date",snap.date);v.put("sender_id",snap.sender);v.put("is_out",snap.outgoing?1:0);
    if(snap.savedMedia!=null){
      v.put("media_path",snap.savedMedia);
      v.put("media_bytes",new java.io.File(snap.savedMedia).length());
    }
    if(old==null){
      v.put("deleted",0);
      d.insertWithOnConflict("messages",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }else if(deleted==0&&snap.editDate>=oldEdit&&!old.equals(snap.text)){
      // A different snapshot without an edit_date is not a Telegram edit.
      if(snap.editDate>0&&MelgramConfig.get(MelgramConfig.HISTORY_EDITS)){
        event(d,snap.account,snap.dialog,snap.id,1,old,snap.text,snap.editDate*1000L);
      }
      d.update("messages",v,"account=? AND dialog=? AND mid=?",args);
    }else if(deleted==0&&snap.savedMedia!=null){
      // Cached attachment may arrive after the first message snapshot.
      ContentValues media=new ContentValues();
      media.put("media_path",snap.savedMedia);
      media.put("media_bytes",new java.io.File(snap.savedMedia).length());
      d.update("messages",media,"account=? AND dialog=? AND mid=?",args);
    }
  }
  public static void capture(int account,TLRPC.Message message){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||message==null)return;
    captureBatch(account,java.util.Collections.singletonList(message));
  }
  public static void captureBatch(int account,List<TLRPC.Message> messages){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||messages==null)return;
    final ArrayList<Snapshot> copies=new ArrayList<>();
    for(TLRPC.Message message:messages){
      Snapshot item=snapshot(account,message);
      if(item!=null)copies.add(item);
    }
    if(copies.isEmpty())return;
    queue.execute(()->{
      // Copy cached files before opening the SQLite transaction. This can
      // take time and must never hold the database lock during disk I/O.
      for(Snapshot item:copies){
        if(item.mediaSource!=null){
          item.savedMedia=MelgramMediaArchive.persist(item.account,item.dialog,item.id,item.mediaSource);
        }
      }
      synchronized(lock){
      SQLiteDatabase database=null;
      boolean transaction=false;
      try{
        database=db();if(database==null)return;
        database.beginTransaction();
        transaction=true;
        for(Snapshot item:copies)save(database,item);
        database.setTransactionSuccessful();
      }catch(Exception e){FileLog.e(e);}
      finally{
        if(transaction&&database!=null){
          try{database.endTransaction();}catch(Exception e){FileLog.e(e);}
        }
      }
    }});
  }
  public static void markDeleted(int account,long dialog,List<Integer> ids){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)||ids==null||dialog==0||!MelgramConfig.historyDialogEnabled(account,dialog))return;
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
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)||!MelgramConfig.historyDialogEnabled(a,dialog))return;
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
  /** Unified asynchronous event browser for the history settings screen. */
  public static void loadEvents(int account,long dialog,int messageId,int limit,
      java.util.function.Consumer<ArrayList<Entry>> callback) {
    queue.execute(()->{
      ArrayList<Entry> entries=messageId>0?forMessage(account,dialog,messageId):recent(account,dialog,limit);
      if(callback!=null)AndroidUtilities.runOnUIThread(()->callback.accept(entries));
    });
  }

  public static void clear(int a){
    clearArchive(a,0,null);
  }

  /**
   * AyuGram-style in-chat recovery. The archive is independent of Telegram's
   * disposable cache. Restore locally deleted text/captions only for the range
   * of message IDs that Telegram is currently loading.
   *
   * A bounded, indexed lookup prevents pathological full-history scans.
   */
  public static void injectDeletedBatch(int account,long dialog,ArrayList<MessageObject> loaded,boolean isChannel){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
      ||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)
      ||!MelgramConfig.historyDialogEnabled(account,dialog)
      ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
      ||DialogObject.isEncryptedDialog(dialog)||loaded==null||loaded.size()<2)return;
    int min=Integer.MAX_VALUE,max=0;
    HashSet<Integer> present=new HashSet<>();
    for(MessageObject item:loaded){
      if(item==null||item.getId()<=0)continue;
      present.add(item.getId());
      min=Math.min(min,item.getId());max=Math.max(max,item.getId());
    }
    if(min>=max)return;
    ArrayList<MessageObject> restored=new ArrayList<>();
    synchronized(lock){
      try{
        SQLiteDatabase database=db();
        if(database==null)return;
        Cursor c=database.rawQuery(
          "SELECT mid,text,msg_date,sender_id,is_out FROM messages "
          +"WHERE account=? AND dialog=? AND deleted=1 AND mid>=? AND mid<=? AND msg_date>0 "
          +"ORDER BY mid DESC LIMIT 60",
          new String[]{""+account,""+dialog,""+min,""+max});
        try{
          while(c.moveToNext()){
            final int mid=c.getInt(0);
            if(present.contains(mid))continue;
            TLRPC.TL_message msg=new TLRPC.TL_message();
            msg.id=mid;msg.dialog_id=dialog;msg.date=c.getInt(2);
            msg.message=c.getString(1);
            msg.out=c.getInt(4)==1;
            if(dialog>0){
              TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();
              peer.user_id=dialog;msg.peer_id=peer;
            }else if(isChannel){
              TLRPC.TL_peerChannel peer=new TLRPC.TL_peerChannel();
              peer.channel_id=-dialog;msg.peer_id=peer;
            }else{
              TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();
              peer.chat_id=-dialog;msg.peer_id=peer;
            }
            long sender=c.getLong(3);
            if(sender==0&&dialog>0&&!msg.out)sender=dialog;
            if(sender==0&&msg.out)sender=UserConfig.getInstance(account).getClientUserId();
            if(sender!=0){
              if(sender>0){
                TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();
                peer.user_id=sender;msg.from_id=peer;
              }else if(isChannel){
                TLRPC.TL_peerChannel peer=new TLRPC.TL_peerChannel();
                peer.channel_id=-sender;msg.from_id=peer;
              }else{
                TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();
                peer.chat_id=-sender;msg.from_id=peer;
              }
              msg.flags|=TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
            }
            MessageObject wrapper=new MessageObject(account,msg,true,false);
            restored.add(wrapper);
            present.add(mid);
          }
        }finally{c.close();}
      }catch(Exception e){FileLog.e(e);}
    }
    if(restored.isEmpty())return;
    loaded.addAll(restored);
    Collections.sort(loaded,(a,b)->Integer.compare(b.getId(),a.getId()));
  }

  /** Read-only snapshot of the locally captured message, loaded on the history worker. */
  public static final class Detail {
    public final ArrayList<Entry> events;
    public final String currentText;
    public final int originalDate;
    public final long sender;
    public final boolean outgoing;
    public final boolean deleted;
    public final String mediaPath;
    Detail(ArrayList<Entry> events,String currentText,int originalDate,long sender,boolean outgoing,boolean deleted,String mediaPath) {
      this.events=events;this.currentText=currentText;this.originalDate=originalDate;
      this.sender=sender;this.outgoing=outgoing;this.deleted=deleted;this.mediaPath=mediaPath;
    }
  }

  public static void loadDetail(int a,long dialog,int mid,java.util.function.Consumer<Detail> callback) {
    queue.execute(()->{
      ArrayList<Entry> events=new ArrayList<>();
      String value=null,mediaPath=null;
      int date=0;
      long sender=0;
      boolean outgoing=false,deleted=false;
      synchronized(lock){
        try {
          SQLiteDatabase database=db();
          if(database!=null) {
            String[] args={""+a,""+dialog,""+mid};
            Cursor m=database.rawQuery("SELECT text,msg_date,sender_id,is_out,deleted,media_path FROM messages WHERE account=? AND dialog=? AND mid=? LIMIT 1",args);
            try {
              if(m.moveToFirst()){
                value=m.getString(0);date=m.getInt(1);sender=m.getLong(2);
                outgoing=m.getInt(3)!=0;deleted=m.getInt(4)!=0;mediaPath=m.getString(5);
              }
            } finally {m.close();}
            Cursor e=database.rawQuery("SELECT dialog,mid,kind,before_text,after_text,time FROM events WHERE account=? AND dialog=? AND mid=? ORDER BY rowid ASC LIMIT 100",args);
            try {while(e.moveToNext())events.add(new Entry(e));} finally {e.close();}
          }
        }catch(Exception ex){FileLog.e(ex);}
      }
      Detail result=new Detail(events,value,date,sender,outgoing,deleted,mediaPath);
      if(callback!=null)AndroidUtilities.runOnUIThread(()->callback.accept(result));
    });
  }

  public static final class ArchiveStats {
    public final int messages,edits,deletions,mediaCount;
    public final long databaseBytes,mediaBytes;
    ArchiveStats(int messages,int edits,int deletions,int mediaCount,long databaseBytes,long mediaBytes){
      this.messages=messages;this.edits=edits;this.deletions=deletions;
      this.mediaCount=mediaCount;this.databaseBytes=databaseBytes;this.mediaBytes=mediaBytes;
    }
  }

  private static int count(SQLiteDatabase d,String table,String condition,String[] args){
    Cursor c=d.rawQuery("SELECT COUNT(*) FROM "+table+" WHERE "+condition,args);
    try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}
  }

  public static void loadStats(int account,long dialog,java.util.function.Consumer<ArchiveStats> callback) {
    queue.execute(()->{
      int messages=0,edits=0,deletions=0,mediaCount=0;
      long mediaBytes=0,dbBytes=0;
      synchronized(lock){
        try {
          SQLiteDatabase d=db();
          if(d!=null){
            String filter="account=?"+(dialog==0?"":" AND dialog=?");
            String[] args=dialog==0?new String[]{""+account}:new String[]{""+account,""+dialog};
            messages=count(d,"messages",filter,args);
            edits=count(d,"events",filter+" AND kind=1",args);
            deletions=count(d,"events",filter+" AND kind=2",args);
            Cursor c=d.rawQuery("SELECT COUNT(*),COALESCE(SUM(media_bytes),0) FROM messages WHERE "+filter+" AND media_path IS NOT NULL",args);
            try {if(c.moveToFirst()){mediaCount=c.getInt(0);mediaBytes=c.getLong(1);}}finally{c.close();}
            java.io.File f=ApplicationLoader.applicationContext.getDatabasePath("melgram_history.db");
            dbBytes=f.length();
          }
        }catch(Exception ex){FileLog.e(ex);}
      }
      ArchiveStats stats=new ArchiveStats(messages,edits,deletions,mediaCount,dbBytes,mediaBytes);
      if(callback!=null)AndroidUtilities.runOnUIThread(()->callback.accept(stats));
    });
  }

  private static void clearRows(SQLiteDatabase database,int account,long dialog){
    String[] args=dialog==0?new String[]{""+account}:new String[]{""+account,""+dialog};
    String cond="account=?"+(dialog==0?"":" AND dialog=?");
    Cursor media=database.rawQuery("SELECT media_path FROM messages WHERE "+cond+" AND media_path IS NOT NULL",args);
    try {while(media.moveToNext())MelgramMediaArchive.delete(media.getString(0));}
    finally {media.close();}
    database.delete("messages",cond,args);
    database.delete("events",cond,args);
    String prefix=dialog==0?account+":":account+":"+dialog+":";
    for(String key:new ArrayList<>(badges.keySet()))if(key.startsWith(prefix))badges.remove(key);
    for(String key:new ArrayList<>(badgeObservers.keySet()))if(key.startsWith(prefix))badgeObservers.remove(key);
  }

  /** Clear only saved attachments; message edits and deleted text stay intact. */
  public static void clearMediaArchive(int account,long dialog,Runnable callback){
    queue.execute(()->{
      synchronized(lock){
        try {
          SQLiteDatabase database=db();
          if(database!=null){
            String where="account=?"+(dialog==0?"":" AND dialog=?");
            String[] args=dialog==0?new String[]{""+account}:new String[]{""+account,""+dialog};
            Cursor c=database.rawQuery("SELECT media_path FROM messages WHERE "+where+" AND media_path IS NOT NULL",args);
            try { while(c.moveToNext())MelgramMediaArchive.delete(c.getString(0)); }
            finally { c.close(); }
            ContentValues v=new ContentValues();
            v.putNull("media_path");v.put("media_bytes",0);
            database.update("messages",v,where,args);
          }
        }catch(Exception e){FileLog.e(e);}
      }
      if(callback!=null)AndroidUtilities.runOnUIThread(callback);
    });
  }

  /** Per-chat/account cleanup never deletes another account's archive. */
  public static void clearArchive(int account,long dialog,Runnable callback){
    queue.execute(()->{
      synchronized(lock){
        try {
          SQLiteDatabase database=db();
          if(database!=null){
            database.beginTransaction();
            try {clearRows(database,account,dialog);database.setTransactionSuccessful();}
            finally {database.endTransaction();}
            // Reclaim SQLite pages after deleting the archive, outside a transaction.
            try {database.execSQL("VACUUM");}catch(Exception e){FileLog.e(e);}
          }
        } catch(Exception ex){FileLog.e(ex);}
      }
      if(callback!=null)AndroidUtilities.runOnUIThread(callback);
    });
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
    return MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)&&(badgeState(a,dialog,mid)&2)!=0;
  }
  public static boolean hasEdits(int a,long dialog,int mid){
    return MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)&&(badgeState(a,dialog,mid)&1)!=0;
  }
  /**
   * AyuGram-style timestamp marks are part of the measured text, never painted
   * on top of the bubble. Both edit and deletion flags can be visible together.
   */
  private static void styleDeletedBubble(android.view.View cell,boolean deleted) {
    if(cell==null)return;
    // AyuGram-like dimmed bubbles, but retain readability and correct touch events.
    // Every message bind recalculates alpha so recycled cells return to normal.
    float alpha=deleted?0.65f:1.0f;
    if(Math.abs(cell.getAlpha()-alpha)>0.01f)cell.setAlpha(alpha);
  }

  public static String formatAyuTime(int account,MessageObject message,boolean telegramEdited,String time,android.view.View cell){
    if(message==null||message.messageOwner==null||message.getId()<=0
       ||time==null||time.isEmpty()
       ||!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
       ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
       ||message.messageOwner.edit_hide||message.scheduled||message.notime
       ||message.isSponsored()||message.isQuickReply()||message.isWelcomeMessage()){
      styleDeletedBubble(cell,false);
      return time;
    }
    long dialog=message.getDialogId();
    if(DialogObject.isEncryptedDialog(dialog)){
      styleDeletedBubble(cell,false);
      return time;
    }
    if(dialog>0&&!MelgramConfig.get(MelgramConfig.HISTORY_BOTS)){
      TLRPC.User user=MessagesController.getInstance(account).getUser(dialog);
      if(user!=null&&user.bot){
        styleDeletedBubble(cell,false);
        return time;
      }
    }
    int flags=statusForView(account,dialog,message.getId(),cell);
    boolean deleted=MelgramConfig.get(MelgramConfig.HISTORY_DELETES)&&(flags&2)!=0;
    boolean edited=telegramEdited||(MelgramConfig.get(MelgramConfig.HISTORY_EDITS)&&(flags&1)!=0);
    styleDeletedBubble(cell,deleted);
    if(!deleted&&!edited)return time;
    // Marks are inside the measured timestamp; colors follow the native theme.
    return (edited?"✎ ":"")+(deleted?"✕ ":"")+time;
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
  /** Legacy compatibility helpers: the new patch no longer intercepts native deletes. */
  public static void retainDeletedInStorage(int account,long dialog,ArrayList<Integer> ids){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
      ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
      ||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)
      ||dialog==0||ids==null||ids.isEmpty())return;
    ArrayList<Integer> retained=new ArrayList<>();
    for(Integer mid:ids){
      if(mid!=null&&exists(account,dialog,mid,false))retained.add(mid);
    }
    if(!retained.isEmpty()){
      markDeleted(account,dialog,retained);
      ids.removeAll(retained);
    }
  }
  /** Filter only the currently opened chat, never every dialog with the same id. */
  @SuppressWarnings("unchecked")
  public static void filterChatDeleteEvent(int account,long dialog,Object[] args){
    if(!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
       ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
       ||!MelgramConfig.get(MelgramConfig.HISTORY_DELETES)
       ||dialog==0||DialogObject.isEncryptedDialog(dialog)
       ||args==null||args.length<3||Boolean.TRUE.equals(args[2])
       ||!(args[0] instanceof ArrayList)||!(args[1] instanceof Long))return;
    long channel=(Long)args[1];
    if(channel>0&&dialog!=-channel)return;
    for(Object mid:(ArrayList<?>)args[0])if(!(mid instanceof Integer))return;
    ArrayList<Integer> original=(ArrayList<Integer>)args[0];
    ArrayList<Integer> retained=new ArrayList<>();
    for(Integer mid:original){
      if(mid!=null&&exists(account,dialog,mid,false))retained.add(mid);
    }
    if(!retained.isEmpty()){
      ArrayList<Integer> remaining=new ArrayList<>(original);
      remaining.removeAll(retained);
      args[0]=remaining;
      markDeleted(account,dialog,retained);
    }
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