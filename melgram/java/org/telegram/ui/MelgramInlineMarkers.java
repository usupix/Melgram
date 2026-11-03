package org.telegram.ui;
import android.view.View;
import android.view.ViewGroup;
import org.telegram.messenger.*;
import java.util.ArrayList;

/** Refreshes visible message bubbles after locally retained delete events. */
public final class MelgramInlineMarkers {
    private MelgramInlineMarkers() {}
    private static final java.util.Set<String> pendingRefreshes =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Coalesce archive writes into a single native adapter rebind. The
     * background worker calls this after creating an edit/delete event.
     */
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
        },120);
    }

    public static void refresh(ArrayList<MessageObject> messages, View root, int account) {
        if (root != null && MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
                && MelgramConfig.get(MelgramConfig.HISTORY_INLINE)) refreshViews(root);
    }
    private static void refreshViews(View view) {
        // Refresh RecyclerView via its adapter so message widths, timestamp
        // layout, grouped media and hit targets are recomputed by Telegram.
        if (view instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView list = (androidx.recyclerview.widget.RecyclerView) view;
            boolean hasMessages = false;
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i) instanceof org.telegram.ui.Cells.ChatMessageCell) {
                    hasMessages = true;
                    break;
                }
            }
            if (hasMessages) {
                list.post(() -> {
                    if (list.getAdapter() != null) list.getAdapter().notifyDataSetChanged();
                });
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                refreshViews(parent.getChildAt(i));
            }
        }
    }

}
