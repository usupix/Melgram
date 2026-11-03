package org.telegram.ui;
import android.view.View;
import android.view.ViewGroup;
import org.telegram.messenger.*;
import java.util.ArrayList;

/** Refreshes visible message bubbles after locally retained delete events. */
public final class MelgramInlineMarkers {
    private MelgramInlineMarkers() {}
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
