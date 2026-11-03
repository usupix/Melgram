package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.MelgramMediaArchive;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;

/**
 * Chronological edit history, displayed with native Telegram chat bubbles.
 * The first bubble is the captured original; later bubbles contain edits.
 * Queries are performed on MelgramHistory's single background worker.
 */
public final class MelgramRevisionChatFragment extends BaseFragment {
    private static final class Revision {
        final String text;
        final int timestamp;
        Revision(String text,int timestamp) {
            this.text=text==null?"":text;
            this.timestamp=timestamp;
        }
    }

    private final long dialog;
    private final int message;
    private final ArrayList<Revision> revisions=new ArrayList<>();
    private RecyclerListView listView;
    private VersionsAdapter adapter;
    private FrameLayout root;
    private String mediaPath;
    private final ArrayList<String> mediaVersions=new ArrayList<>();
    private long sender;
    private boolean outgoing;
    private boolean destroyed;

    public MelgramRevisionChatFragment(long dialog,int message){
        this.dialog=dialog;
        this.message=message;
    }

    @Override public boolean onFragmentCreate(){
        super.onFragmentCreate();
        MelgramHistory.loadDetail(currentAccount,dialog,message,detail->{
            if(destroyed)return;
            sender=detail.sender;
            outgoing=detail.outgoing;
            mediaPath=detail.mediaPath;
            mediaVersions.clear();
            mediaVersions.addAll(detail.mediaVersions);
            if(mediaVersions.isEmpty()&&mediaPath!=null&&new File(mediaPath).isFile())mediaVersions.add(mediaPath);
            updateTimeline(detail);
            if(actionBar!=null)actionBar.setSubtitle("Сообщение #"+message+" · "+revisions.size()+" версий");
            if(adapter!=null)adapter.notifyDataSetChanged();
            if(root!=null)showMediaLink(root);
        });
        return true;
    }

    @Override public void onFragmentDestroy(){
        destroyed=true;
        super.onFragmentDestroy();
    }

    private void updateTimeline(MelgramHistory.Detail detail) {
        revisions.clear();
        String active=null;
        int original=detail.originalDate>0?detail.originalDate:(int)(System.currentTimeMillis()/1000L);
        int versionDate=original;
        for(MelgramHistory.Entry e:detail.events) {
            if(e.kind==1) {
                String before=e.before==null?"":e.before;
                // A separate revision is saved at every edit, not just the first.
                addVersion(before,versionDate);
                active=e.after==null?"":e.after;
                versionDate=(int)(e.time/1000L);
            }else if(e.kind==2&&active==null) {
                active=e.before==null?"":e.before;
            }
        }
        if(active==null)active=detail.currentText;
        if(active!=null)addVersion(active,versionDate);
    }

    private void addVersion(String text,int date){
        if(!revisions.isEmpty()&&revisions.get(revisions.size()-1).text.equals(text))return;
        revisions.add(new Revision(text,date));
    }

    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("История изменений");
        actionBar.setSubtitle("Сообщение #"+message+" · загрузка…");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){
            @Override public void onItemClick(int id){if(id==-1)finishFragment();}
        });

        root=new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView=root;

        listView=new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setLayoutManager(new LinearLayoutManager(context,LinearLayoutManager.VERTICAL,false){
            @Override public boolean supportsPredictiveItemAnimations(){return false;}
        });
        listView.setVerticalScrollBarEnabled(true);
        root.addView(listView,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.MATCH_PARENT));
        adapter=new VersionsAdapter(context);
        listView.setAdapter(adapter);
        showMediaLink(root);
        return root;
    }

    private void openSavedMedia(String path) {
        Context context=getParentActivity();
        if(context==null)return;
        try{
            File file=new File(path);
            if(!file.isFile())throw new java.io.FileNotFoundException(path);
            Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".provider",file);
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri,MelgramMediaArchive.mimeType(path));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        }catch(Exception ex){
            org.telegram.messenger.FileLog.e(ex);
            android.widget.Toast.makeText(context,"Не удалось открыть вложение",android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showMediaLink(FrameLayout frame){
        if(mediaVersions.isEmpty()||frame.findViewWithTag("saved_attachment")!=null)return;
        Context context=frame.getContext();
        TextView button=new TextView(context);
        button.setTag("saved_attachment");
        button.setText(mediaVersions.size()==1?"Открыть сохранённое вложение":
                "Сохранённые вложения · "+mediaVersions.size());
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        button.setTextSize(15);
        button.setGravity(android.view.Gravity.CENTER);
        button.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        button.setOnClickListener(v->{
            if(mediaVersions.size()==1){
                openSavedMedia(mediaVersions.get(0));
                return;
            }
            CharSequence[] labels=new CharSequence[mediaVersions.size()];
            for(int i=0;i<mediaVersions.size();i++){
                File f=new File(mediaVersions.get(i));
                String type=MelgramMediaArchive.mimeType(f.getAbsolutePath());
                labels[i]="Версия "+(i+1)+" · "+type+" · "+(f.length()/1024L)+" КБ";
            }
            org.telegram.ui.ActionBar.AlertDialog.Builder builder=
                new org.telegram.ui.ActionBar.AlertDialog.Builder(context);
            builder.setTitle("Сохранённые версии вложения");
            builder.setItems(labels,(d,index)->{
                if(index>=0&&index<mediaVersions.size())openSavedMedia(mediaVersions.get(index));
            });
            showDialog(builder.create());
        });
        listView.setPadding(0,AndroidUtilities.dp(48),0,0);
        frame.addView(button,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,48,android.view.Gravity.TOP));
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions(){return new ArrayList<>();}

    private MessageObject versionMessage(Revision revision,int index){
        TLRPC.TL_message msg=new TLRPC.TL_message();
        msg.id=-100000-index;
        msg.dialog_id=dialog;
        msg.date=revision.timestamp;
        msg.message=revision.text.isEmpty()?"[Пустое сообщение]":revision.text;
        msg.edit_hide=true;
        msg.out=outgoing;
        boolean channel=false;
        if(dialog>0){
            TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();
            peer.user_id=dialog;msg.peer_id=peer;
        }else{
            channel=ChatObject.isChannel(MessagesController.getInstance(currentAccount).getChat(-dialog));
            if(channel){
                TLRPC.TL_peerChannel peer=new TLRPC.TL_peerChannel();
                peer.channel_id=-dialog;msg.peer_id=peer;
            }else{
                TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();
                peer.chat_id=-dialog;msg.peer_id=peer;
            }
        }
        long fromId=sender;
        if(fromId==0&&outgoing)fromId=UserConfig.getInstance(currentAccount).getClientUserId();
        if(fromId==0&&dialog>0)fromId=dialog;
        if(fromId!=0){
            if(fromId>0){
                TLRPC.TL_peerUser from=new TLRPC.TL_peerUser();
                from.user_id=fromId;msg.from_id=from;
            }else if(channel){
                TLRPC.TL_peerChannel from=new TLRPC.TL_peerChannel();
                from.channel_id=-fromId;msg.from_id=from;
            }else{
                TLRPC.TL_peerChat from=new TLRPC.TL_peerChat();
                from.chat_id=-fromId;msg.from_id=from;
            }
            msg.flags|=TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        }
        return new MessageObject(currentAccount,msg,false,false);
    }

    private final class VersionsAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        VersionsAdapter(Context context){this.context=context;}
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder){return true;}
        @Override public int getItemCount(){return revisions.size();}
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
            ChatMessageCell cell=new ChatMessageCell(context,currentAccount);
            cell.setFullyDraw(true);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate(){});
            cell.setOnClickListener(view->{
                int i=(int)view.getTag();
                if(i>=0&&i<revisions.size())AndroidUtilities.addToClipboard(revisions.get(i).text);
            });
            cell.setOnLongClickListener(view->{
                int i=(int)view.getTag();
                if(i>=0&&i<revisions.size())AndroidUtilities.addToClipboard(revisions.get(i).text);
                return true;
            });
            return new RecyclerListView.Holder(cell);
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){
            ChatMessageCell cell=(ChatMessageCell)holder.itemView;
            cell.setTag(position);
            cell.setMessageObject(versionMessage(revisions.get(position),position),null,false,false,false);
        }
    }
}
