package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.MessageObject;

/**
 * Small status icon beside the original timestamp. The message text, bubble and
 * timestamp remain untouched. The local-history lookup runs off the UI thread.
 */
public final class MelgramBubbleOverlay {
    private static final Paint ICON = new Paint(Paint.ANTI_ALIAS_FLAG);

    private MelgramBubbleOverlay() {}

    public static void draw(Canvas canvas, MessageObject message, float timeX, float timeY, View cell) {
        if (message == null || message.messageOwner == null || cell == null
                || !MelgramConfig.get(MelgramConfig.HISTORY_ENABLED)
                || !MelgramConfig.get(MelgramConfig.HISTORY_INLINE)) {
            return;
        }

        int status = MelgramHistory.statusForView(
                message.currentAccount, message.getDialogId(), message.getId(), cell);
        if (status != 1 && status != 2) {
            return;
        }

        float dp = AndroidUtilities.density;
        float x = timeX - 13f * dp;
        if (x < 4f * dp || timeY < 0f) {
            return;
        }
        ICON.setTextSize(11f * dp);
        ICON.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ICON.setTextAlign(Paint.Align.LEFT);
        ICON.setColor(status == 2 ? 0xffe25763 : 0xff55a9de);
        ICON.setAlpha(255);
        canvas.drawText(status == 2 ? "✕" : "✎", x, timeY + 11f * dp, ICON);
    }
}
