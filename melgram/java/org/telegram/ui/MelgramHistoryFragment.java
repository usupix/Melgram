package org.telegram.ui;
import android.view.View;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;
import java.util.*;
public class MelgramHistoryFragment extends UniversalFragment {
  private static final int CLEAR=1,ITEM=1000;
  private final long dialog;
  private final ArrayList<MelgramHistory.Entry> entries=new ArrayList<>();
  public MelgramHistoryFragment(){this(0);}
  public MelgramHistoryFragment(long dialog){this.dialog=dialog;}
  @Override protected CharSequence getTitle(){return dialog==0?"История сообщений":"История чата";}
  @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
    entries.clear();entries.addAll(MelgramHistory.recent(UserConfig.selectedAccount,dialog,200));
    items.add(UItem.asHeader("Удалённые и изменённые"));
    items.add(UItem.asShadow("История хранится на устройстве отдельно от кеша Telegram. Сохраняется только текст и подписи."));
    if(entries.isEmpty())items.add(UItem.asShadow("История пуста. Включи сохранение, чтобы видеть новые удаления и правки."));
    for(int i=0;i<entries.size();i++){
      MelgramHistory.Entry e=entries.get(i);
      String txt=e.before==null?"":e.before.replace('\n',' ');
      if(txt.length()>85)txt=txt.substring(0,85)+"…";
      items.add(UItem.asButton(ITEM+i,e.kind==2?"Удалено · #"+e.mid:"Изменено · #"+e.mid,(dialog==0?"Чат "+e.dialog+" · ":"")+txt));
    }
    items.add(UItem.asButton(CLEAR,R.drawable.msg_delete,"Очистить историю"));
  }
  @Override protected void onClick(UItem item,View view,int position,float x,float y){
    if(getParentActivity()==null)return;
    if(item.id==CLEAR){
      AlertDialog.Builder b=new AlertDialog.Builder(getParentActivity());
      b.setTitle("Очистить локальную историю?");
      b.setMessage("Все сохранённые изменения и удаления текущего аккаунта будут удалены.");
      b.setPositiveButton("Удалить",(d,w)->{MelgramHistory.clear(UserConfig.selectedAccount);listView.adapter.update(true);});
      b.setNegativeButton("Отмена",null);showDialog(b.create());
    }else if(item.id>=ITEM&&item.id<ITEM+entries.size()){
      MelgramHistory.Entry e=entries.get(item.id-ITEM);
      AlertDialog.Builder b=new AlertDialog.Builder(getParentActivity());
      b.setTitle(e.kind==2?"Удалённое сообщение":"История изменений");
      b.setMessage("Чат: "+e.dialog+"\nСообщение: "+e.mid+"\n\nБыло:\n"+e.before+(e.kind==1?"\n\nСтало:\n"+e.after:""));
      b.setPositiveButton("Закрыть",null);showDialog(b.create());
    }
  }
  @Override protected boolean onLongClick(UItem item,View v,int p,float x,float y){return false;}
}