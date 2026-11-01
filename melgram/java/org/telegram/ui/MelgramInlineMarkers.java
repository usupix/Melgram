package org.telegram.ui;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
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
        if (view instanceof RecyclerView) {
            RecyclerView.Adapter adapter = ((RecyclerView) view).getAdapter();
            if (adapter != null) adapter.notifyDataSetChanged();
        } else if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                refreshViews(parent.getChildAt(i));
            }
        }
    }
}
