package org.telegram.ui;
import android.view.View;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;
import java.util.*;
public class MelgramHistoryFragment extends UniversalFragment {
  private static final int CLEAR=1,CHAT_SAVE=2,CHAT_CLEAR=3,ITEM=1000;
  private final long dialog;
  private final int messageId;
  private final ArrayList<MelgramHistory.Entry> entries=new ArrayList<>();
  public MelgramHistoryFragment(){this(0,0);}
  public MelgramHistoryFragment(long dialog){this(dialog,0);}
  public MelgramHistoryFragment(long dialog,int messageId){this.dialog=dialog;this.messageId=messageId;}
  @Override protected CharSequence getTitle(){return messageId>0?"История правок":dialog==0?"История сообщений":"История чата";}
  @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
    entries.clear();entries.addAll(messageId>0?MelgramHistory.forMessage(currentAccount,dialog,messageId):MelgramHistory.recent(currentAccount,dialog,200));
    if(dialog!=0){
      items.add(UItem.asHeader("Архив этого чата"));
      items.add(UItem.asButtonCheck(CHAT_SAVE,"Сохранять историю в этом чате","Старые записи останутся в архиве").setChecked(MelgramConfig.historyDialogEnabled(currentAccount,dialog)));
      items.add(UItem.asButton(CHAT_CLEAR,R.drawable.msg_delete,"Очистить этот чат"));
    }
    items.add(UItem.asHeader("Удалённые и изменённые"));
    items.add(UItem.asShadow("История хранится на устройстве отдельно от кеша Telegram. Сохраняется только текст и подписи."));
    if(entries.isEmpty())items.add(UItem.asShadow("История пуста. Включи сохранение, чтобы видеть новые удаления и правки."));
    for(int i=0;i<entries.size();i++){
      MelgramHistory.Entry e=entries.get(i);
      String txt=e.before==null?"":e.before.replace('\n',' ');
      if(txt.length()>85)txt=txt.substring(0,85)+"…";
      items.add(UItem.asButton(ITEM+i,e.kind==2?"Удалено · #"+e.mid+" · "+new java.text.SimpleDateFormat("dd.MM HH:mm").format(new Date(e.time)):"Изменено · #"+e.mid+" · "+new java.text.SimpleDateFormat("dd.MM HH:mm").format(new Date(e.time)),(dialog==0?"Чат "+e.dialog+" · ":"")+txt));
    }
    items.add(UItem.asButton(CLEAR,R.drawable.msg_delete,"Очистить историю"));
  }
  @Override protected void onClick(UItem item,View view,int position,float x,float y){
    if(getParentActivity()==null)return;
    if(item.id==CHAT_SAVE){
      MelgramConfig.setHistoryDialogEnabled(currentAccount,dialog,!MelgramConfig.historyDialogEnabled(currentAccount,dialog));
      listView.adapter.update(true);
    }else if(item.id==CHAT_CLEAR){
      AlertDialog.Builder b=new AlertDialog.Builder(getParentActivity());
      b.setTitle("Очистить локальную историю этого чата?");
      b.setMessage("Записи других чатов и аккаунтов останутся без изменений.");
      b.setPositiveButton("Удалить",(d,w)->MelgramHistory.clearArchive(currentAccount,dialog,()->listView.adapter.update(true)));
      b.setNegativeButton("Отмена",null);showDialog(b.create());
    }else if(item.id==CLEAR){
      AlertDialog.Builder b=new AlertDialog.Builder(getParentActivity());
      b.setTitle("Очистить локальную историю?");
      b.setMessage("Все сохранённые изменения и удаления текущего аккаунта будут удалены.");
      b.setPositiveButton("Удалить",(d,w)->{MelgramHistory.clearArchive(currentAccount,0,()->listView.adapter.update(true));});
      b.setNegativeButton("Отмена",null);showDialog(b.create());
    }else if(item.id>=ITEM&&item.id<ITEM+entries.size()){
      MelgramHistory.Entry e=entries.get(item.id-ITEM);
      presentFragment(new MelgramRevisionChatFragment(e.dialog,e.mid));
    }
  }
  @Override protected boolean onLongClick(UItem item,View v,int p,float x,float y){return false;}
}