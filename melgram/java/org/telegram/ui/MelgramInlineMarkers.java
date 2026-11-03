package org.telegram.ui;

import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatMessageCell;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rebind visible message bubbles after local archive changes.
 *
 * A normal notifyDataSetChanged is not sufficient: Telegram skips the expensive
 * ChatMessageCell time calculation when it sees the same MessageObject. We mark
 * only affected messages forceUpdate and refresh their actual RecyclerView rows.
 */
public final class MelgramInlineMarkers {
    private MelgramInlineMarkers() {}

    private static final Set<String> pendingRefreshes=ConcurrentHashMap.newKeySet();

    public static void onArchiveChanged(int account,long dialog) {
        final String key=account+":"+dialog;
        if(!pendingRefreshes.add(key))return;
        AndroidUtilities.runOnUIThread(()->{
            pendingRefreshes.remove(key);
            org.telegram.ui.ActionBar.BaseFragment top=LaunchActivity.getSafeLastFragment();
            if(!(top instanceof ChatActivity)
                    ||top.getCurrentAccount()!=account
                    ||((ChatActivity)top).getDialogId()!=dialog)return;
            refresh(null,top.getFragmentView(),account);
        },80);
    }

    /** Also called directly by ChatActivity when a local deletion is retained. */
    public static void refresh(ArrayList<MessageObject> messages,View root,int account) {
        if(root==null||!MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
                ||!MelgramConfig.get(MelgramConfig.HISTORY_INLINE))return;
        refreshViews(root,account);
    }

    private static void refreshViews(View view,int account) {
        if(view instanceof RecyclerView){
            RecyclerView list=(RecyclerView)view;
            boolean found=false;
            for(int i=0;i<list.getChildCount();i++){
                if(list.getChildAt(i) instanceof ChatMessageCell){
                    found=true;
                    break;
                }
            }
            if(found){
                // Post outside RecyclerView's layout/notification dispatch to
                // avoid IllegalStateException during a Telegram delete event.
                list.post(()->refreshVisibleCells(list,account));
                return;
            }
        }
        if(view instanceof ViewGroup){
            ViewGroup parent=(ViewGroup)view;
            for(int i=0;i<parent.getChildCount();i++)
                refreshViews(parent.getChildAt(i),account);
        }
    }

    private static void refreshVisibleCells(RecyclerView list,int account){
        RecyclerView.Adapter adapter=list.getAdapter();
        if(adapter==null)return;
        org.telegram.ui.ActionBar.BaseFragment top=LaunchActivity.getSafeLastFragment();
        boolean selecting=top instanceof ChatActivity
                && top.getActionBar()!=null && top.getActionBar().isActionModeShowed();
        for(int i=0;i<list.getChildCount();i++){
            View child=list.getChildAt(i);
            if(!(child instanceof ChatMessageCell))continue;
            ChatMessageCell cell=(ChatMessageCell)child;
            MessageObject message=cell.getPrimaryMessageObject();
            if(message==null||message.currentAccount!=account||message.getId()<=0)continue;
            // Telegram's delete-confirmation callback hides the action bar,
            // but keeping the bubble means the old selection cell survives.
            // Clear the checkbox explicitly when selection mode has closed.
            if(!selecting){
                cell.setDrawSelectionBackground(false);
                cell.setChecked(false,false,false);
                cell.setCheckBoxVisible(false,false);
            }
            int flags=MelgramHistory.cachedStatus(account,message.getDialogId(),message.getId());
            boolean deleted=MelgramConfig.get(MelgramConfig.HISTORY_DELETES)&&(flags&2)!=0;
            float alpha=deleted?0.65f:1.0f;
            boolean alphaChanged=Math.abs(cell.getAlpha()-alpha)>0.01f;
            // Edited messages also need a new measured timestamp string.
            if(flags==0&&!alphaChanged)continue;
            if(alphaChanged)cell.setAlpha(alpha);
            message.forceUpdate=true;
            int position=list.getChildAdapterPosition(cell);
            if(position!=RecyclerView.NO_POSITION)adapter.notifyItemChanged(position);
            else {
                cell.forceResetMessageObject();
                cell.requestLayout();
            }
        }
    }
}
