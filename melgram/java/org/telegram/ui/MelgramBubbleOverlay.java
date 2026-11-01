package org.telegram.ui;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramHistory;
import org.telegram.messenger.MessageObject;

/** Tiny non-interactive status chip on an existing Telegram message bubble. */
public final class MelgramBubbleOverlay {
    private static final Paint BACK = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private MelgramBubbleOverlay() {}
    public static void draw(Canvas canvas, MessageObject message, int width) {
        if (message == null || message.messageOwner == null ||
                !MelgramConfig.get(MelgramConfig.HISTORY_ENABLED) ||
                !MelgramConfig.get(MelgramConfig.HISTORY_INLINE)) return;
        boolean deleted = MelgramHistory.wasDeleted(message.currentAccount, message.getDialogId(), message.getId());
        boolean edited = !deleted && MelgramHistory.hasEdits(message.currentAccount, message.getDialogId(), message.getId());
        if (!deleted && !edited) return;
        float scale = AndroidUtilities.density;
        float w = 78 * scale, h = 20 * scale;
        float x = message.isOutOwner() ? width - w - 14 * scale : 48 * scale;
        float y = 4 * scale;
        BACK.setColor(deleted ? 0xffb72b43 : 0xff2f77aa);
        canvas.drawRoundRect(new RectF(x,y,x+w,y+h),10*scale,10*scale,BACK);
        TEXT.setColor(0xffffffff);
        TEXT.setTextSize(11*scale);
        TEXT.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TEXT.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(deleted ? "🗑 Удалено" : "✎ Изменено", x+w/2f, y+14*scale, TEXT);
    }
}
