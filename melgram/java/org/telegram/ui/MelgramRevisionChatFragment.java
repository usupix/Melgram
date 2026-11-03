package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.MessageObject;
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

import java.util.ArrayList;
import java.util.Collections;

/**
 * A message-shaped history viewer, adapted conceptually from AyuGram's
 * revision timeline. This displays previously received TEXT and captions,
 * not reconstructed attachments.
 */
public final class MelgramRevisionChatFragment extends BaseFragment {
    private static final class Revision {
        final String text;
        final int timestamp;
        Revision(String text,long millis) {
            this.text=text==null?"":text;
            this.timestamp=(int)(millis/1000L);
        }
    }
    private final long dialog;
    private final int message;
    private final ArrayList<Revision> revisions=new ArrayList<>();

    public MelgramRevisionChatFragment(long dialog,int message){
        this.dialog=dialog;
        this.message=message;
    }

    @Override public boolean onFragmentCreate(){
        super.onFragmentCreate();
        ArrayList<MelgramHistory.Entry> events=
                MelgramHistory.forMessage(currentAccount,dialog,message);
        Collections.reverse(events);
        String previous=null;
        long finalTimestamp=System.currentTimeMillis();
        for(MelgramHistory.Entry event:events){
            finalTimestamp=event.time;
            if(event.kind==1){
                if(previous==null||!previous.equals(event.before)){
                    revisions.add(new Revision(event.before,event.time));
                }
                previous=event.after;
            }else if(event.kind==2){
                if(previous==null)previous=event.before;
            }
        }
        if(previous!=null){
            revisions.add(new Revision(previous,finalTimestamp));
        }
        return true;
    }

    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("История изменений");
        actionBar.setSubtitle("Сообщение #"+message+" · "+revisions.size()+" версий");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){
            @Override public void onItemClick(int id){if(id==-1)finishFragment();}
        });

        FrameLayout root=new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView=root;

        RecyclerListView list=new RecyclerListView(context);
        list.setItemAnimator(null);
        list.setLayoutAnimation(null);
        list.setLayoutManager(new LinearLayoutManager(context,LinearLayoutManager.VERTICAL,false){
            @Override public boolean supportsPredictiveItemAnimations(){return false;}
        });
        list.setVerticalScrollBarEnabled(true);
        root.addView(list,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.MATCH_PARENT));
        list.setAdapter(new VersionsAdapter(context));
        return root;
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions(){
        return new ArrayList<>();
    }

    private MessageObject versionMessage(Revision revision,int index){
        TLRPC.TL_message msg=new TLRPC.TL_message();
        msg.id=-100000-index;
        msg.dialog_id=dialog;
        msg.date=revision.timestamp;
        msg.message=revision.text.isEmpty()?"[Пустое сообщение]":revision.text;
        msg.edit_hide=true;
        msg.out=false;
        if(dialog>0){
            TLRPC.TL_peerUser peer=new TLRPC.TL_peerUser();
            peer.user_id=dialog;
            msg.peer_id=peer;
            msg.from_id=peer;
        }else{
            TLRPC.TL_peerChat peer=new TLRPC.TL_peerChat();
            peer.chat_id=-dialog;
            msg.peer_id=peer;
            msg.from_id=peer;
        }
        msg.flags|=TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
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
