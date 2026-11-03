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
    Integer cached=badges.get(key);if(cached!=null&&cached>=0)return cached;
    int result=0;
    synchronized(lock){try{SQLiteDatabase d=db();if(d==null)return 0;
      Cursor c=d.rawQuery("SELECT deleted FROM messages WHERE account=? AND dialog=? AND mid=? LIMIT 1",new String[]{""+a,""+dialog,""+mid});
      try{if(c.moveToFirst()&&c.getInt(0)!=0)result|=2;}finally{c.close();}
      {
        c=d.rawQuery("SELECT 1 FROM events WHERE account=? AND dialog=? AND mid=? AND kind=1 LIMIT 1",new String[]{""+a,""+dialog,""+mid});
        try{if(c.moveToFirst())result|=1;}finally{c.close();}
      }
    }catch(Exception e){FileLog.e(e);}}
    if(badges.size()>12000)badges.clear();
    badges.put(key,result);return result;
  }
  /**
   * Never block Android's drawing thread while a message badge is looked up.
   * Missing badges are fetched on the history worker and the visible cell is invalidated.
   */
  public static int statusForView(int a,long dialog,int mid,android.view.View view){
    String key=badgeKey(a,dialog,mid);
    Integer cached=badges.get(key);
    if(cached!=null)return cached<0?0:cached;
    if(badges.putIfAbsent(key,-1)==null){
      queue.execute(()->{
        badges.remove(key,-1);
        badgeState(a,dialog,mid);
        if(view!=null)AndroidUtilities.runOnUIThread(view::invalidate);
      });
    }
    return 0;
  }
  private static SQLiteOpenHelper helper;
  public static class Entry {
    public long dialog,time; public int mid,kind; public String before,after;
    Entry(Cursor c){dialog=c.getLong(0);mid=c.getInt(1);kind=c.getInt(2);before=c.getString(3);after=c.getString(4);time=c.getLong(5);}
  }
  private static SQLiteDatabase db(){
    Context ctx=ApplicationLoader.applicationContext;
    if(ctx==null)return null;
    if(helper==null)helper=new SQLiteOpenHelper(ctx,"melgram_history.db",null,3){
      public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE messages(account INTEGER,dialog INTEGER,mid INTEGER,text TEXT,edit_date INTEGER,deleted INTEGER DEFAULT 0,msg_date INTEGER DEFAULT 0,sender_id INTEGER DEFAULT 0,is_out INTEGER DEFAULT 0,PRIMARY KEY(account,dialog,mid))");
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
      }
    };
    return helper.getWritableDatabase();
  }
  private static void event(SQLiteDatabase d,int a,long dialog,int id,int kind,String before,String after){
    ContentValues v=new ContentValues();v.put("account",a);v.put("dialog",dialog);v.put("mid",id);v.put("kind",kind);
    v.put("before_text",before);v.put("after_text",after);v.put("time",System.currentTimeMillis());d.insert("events",null,v);
    badges.merge(badgeKey(a,dialog,id),kind==2?2:1,(oldValue,flag)->oldValue|flag);
  }
  // Snapshot the message before Telegram changes its mutable TL objects.
  private static final class Snapshot {
    final int account,id,date,editDate;
    final long dialog,sender;
    final boolean outgoing;
    final String text;
    Snapshot(int account,int id,int date,int editDate,long dialog,long sender,boolean outgoing,String text){
      this.account=account;this.id=id;this.date=date;this.editDate=editDate;
      this.dialog=dialog;this.sender=sender;this.outgoing=outgoing;this.text=text;
    }
  }
  private static Snapshot snapshot(int a,TLRPC.Message m){
    if(m==null||m.id<=0||m.ttl>0||m.noforwards||m.action!=null)return null;
    long did=MessageObject.getDialogId(m);
    if(did==0||DialogObject.isEncryptedDialog(did))return null;
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
    return new Snapshot(a,m.id,m.date,m.edit_date,did,sender,m.out,value);
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
    if(old==null){
      v.put("deleted",0);
      d.insertWithOnConflict("messages",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }else if(deleted==0&&snap.editDate>=oldEdit&&!old.equals(snap.text)){
      // A different snapshot without an edit_date is not a Telegram edit.
      if(snap.editDate>0&&MelgramConfig.get(MelgramConfig.HISTORY_EDITS)){
        event(d,snap.account,snap.dialog,snap.id,1,old,snap.text);
      }
      d.update("messages",v,"account=? AND dialog=? AND mid=?",args);
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
    queue.execute(()->{synchronized(lock){
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
      ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
      ||DialogObject.isEncryptedDialog(dialog)||loaded==null||loaded.size()<2)return;
    int min=Integer.MAX_VALUE,max=0;
    HashSet<Integer> present=new HashSet<>();
    for(MessageObject item:loaded){
      if(item==null||item.getId()<=0)return;
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
  public static String formatAyuTime(int account,MessageObject message,boolean telegramEdited,String time){
    if(message==null||message.messageOwner==null||message.getId()<=0
       ||time==null||time.isEmpty()
       ||!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
       ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
       ||message.messageOwner.edit_hide||message.scheduled||message.notime
       ||message.isSponsored()||message.isQuickReply()||message.isWelcomeMessage())return time;
    long dialog=message.getDialogId();
    if(DialogObject.isEncryptedDialog(dialog))return time;
    if(dialog>0&&!MelgramConfig.get(MelgramConfig.HISTORY_BOTS)){
      TLRPC.User person=MessagesController.getInstance(account).getUser(dialog);
      if(person!=null&&person.bot)return time;
    }
    int flags=badgeState(account,dialog,message.getId());
    boolean deleted=MelgramConfig.get(MelgramConfig.HISTORY_DELETES)&&(flags&2)!=0;
    boolean edited=telegramEdited||(MelgramConfig.get(MelgramConfig.HISTORY_EDITS)&&(flags&1)!=0);
    if(!deleted&&!edited)return time;
    String status=edited?LocaleController.getString(R.string.EditedMessage):"";
    if(deleted)status=edited?status+" (🧹)":"🧹";
    String when=LocaleController.getInstance().getFormatterDay().format((long)message.messageOwner.date*1000);
    return status+" "+when;
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
  /** Preserve only messages belonging to the specific dialog being deleted. */
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