package org.telegram.ui;

import android.view.View;
import android.view.MotionEvent;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.MessageObject;

/** Opens the saved revision history by touching AyuGram-style timestamp marks. */
public final class MelgramBubbleOverlay {
    private static final java.util.WeakHashMap<View,Boolean> pressed =
            new java.util.WeakHashMap<>();

    private MelgramBubbleOverlay() {}

    public static boolean handleTouch(View cell, MotionEvent event, MessageObject message,
                                      float timeX, float timeY, float measuredWidth) {
        if (message == null || message.getId() <= 0 || cell == null || event == null
                || !MelgramConfig.get(MelgramConfig.HISTORY_INLINE)
                || !MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)) {
            return false;
        }
        int status = MelgramHistory.statusForView(
                message.currentAccount, message.getDialogId(), message.getId(), cell);
        if ((status & 3) == 0) {
            pressed.remove(cell);
            return false;
        }

        float dp = AndroidUtilities.density;
        float width = Math.max(measuredWidth, 28f * dp);
        float x = event.getX(), y = event.getY();
        boolean hit = x >= timeX - 4f * dp && x <= timeX + width * dp
                && y >= timeY - 10f * dp && y <= timeY + 20f * dp;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!hit) return false;
            pressed.put(cell, true);
            return true;
        }

        boolean tracked = Boolean.TRUE.equals(pressed.remove(cell));
        if (action == MotionEvent.ACTION_UP && tracked && hit) {
            org.telegram.ui.ActionBar.BaseFragment fragment =
                    LaunchActivity.getSafeLastFragment();
            if (fragment instanceof ChatActivity) {
                fragment.presentFragment(new MelgramRevisionChatFragment(
                        message.getDialogId(), message.getId()));
            }
            return true;
        }
        return tracked;
    }
}
