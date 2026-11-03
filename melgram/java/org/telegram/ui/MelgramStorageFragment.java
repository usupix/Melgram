package org.telegram.ui;

import android.view.View;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;
import java.util.Locale;

/** Local-only storage management, scoped to the current Telegram account. */
public class MelgramStorageFragment extends UniversalFragment {
    private static final int CLEAR=1,HISTORY=2,CLEAR_MEDIA=3;
    private MelgramHistory.ArchiveStats stats;
    private boolean disposed;

    @Override protected CharSequence getTitle(){return "Хранилище Melgram";}

    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        reload();
        return true;
    }

    @Override public void onFragmentDestroy() {
        disposed=true;
        super.onFragmentDestroy();
    }

    private void reload(){
        MelgramHistory.loadStats(currentAccount,0,result->{
            if(disposed)return;
            stats=result;
            if(listView!=null&&listView.adapter!=null)listView.adapter.update(true);
        });
    }

    private String size(long bytes){
        if(bytes>=1024*1024)return String.format(Locale.US,"%.1f МБ",bytes/(1024.0*1024.0));
        return String.format(Locale.US,"%.1f КБ",bytes/1024.0);
    }

    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader("Локальный архив текущего аккаунта"));
        if(stats==null){
            items.add(UItem.asShadow("Подсчёт данных…"));
        }else{
            items.add(UItem.asButton(HISTORY,R.drawable.msg_edit,"Сообщений: "+stats.messages,
                "Правок: "+stats.edits+" · удалений: "+stats.deletions));
            items.add(UItem.asShadow("База данных: "+size(stats.databaseBytes)+
                " (общая для аккаунтов). Сохранённые медиа: "+stats.mediaCount+
                " файлов, "+size(stats.mediaBytes)+"."));
        }
        items.add(UItem.asButton(CLEAR_MEDIA,R.drawable.msg_delete,"Очистить только сохранённые медиа"));
        items.add(UItem.asButton(CLEAR,R.drawable.msg_delete,"Очистить архив этого аккаунта"));
        items.add(UItem.asShadow("Удаляются сохранённые сообщения, история изменений и копии вложений текущего аккаунта. Переписка на серверах Telegram не затрагивается. Сохраняются только медиа, уже скачанные на устройство; лимит файла — 32 МБ, общий лимит архива — 512 МБ."));
    }

    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==HISTORY){
            presentFragment(new MelgramHistoryFragment());
        }else if(item.id==CLEAR_MEDIA&&getParentActivity()!=null){
            AlertDialog.Builder b=new AlertDialog.Builder(getParentActivity());
            b.setTitle("Удалить сохранённые медиа?");
            b.setMessage("Текст и история правок сохранятся. Локальные копии вложений этого аккаунта будут удалены.");
            b.setPositiveButton("Очистить",(d,w)->MelgramHistory.clearMediaArchive(currentAccount,0,()->reload()));
            b.setNegativeButton("Отмена",null);
            showDialog(b.create());
        }else if(item.id==CLEAR&&getParentActivity()!=null){
            AlertDialog.Builder b=new AlertDialog.Builder(getParentActivity());
            b.setTitle("Очистить архив этого аккаунта?");
            b.setMessage("Это удалит сохранённые сообщения, версии и медиа. В Telegram сообщения останутся.");
            b.setPositiveButton("Очистить",(d,w)->MelgramHistory.clearArchive(currentAccount,0,()->reload()));
            b.setNegativeButton("Отмена",null);
            showDialog(b.create());
        }
    }

    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
