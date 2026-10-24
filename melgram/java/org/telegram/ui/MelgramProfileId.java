package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.BulletinFactory;

/**
 * Melgram: an "ID" row in every profile, like in AyuGram. Users show their id;
 * groups and channels show the id in Bot API form (-100… for channels and
 * supergroups), which is what bots and most tools expect. Tap copies it.
 */
public final class MelgramProfileId {

    private MelgramProfileId() {
    }

    /** The id as text, or null when there is nothing to show. */
    public static String idText(int account, long userId, long chatId) {
        if (userId != 0) {
            return String.valueOf(userId);
        }
        if (chatId != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(chatId);
            return ChatObject.isChannel(chat) ? "-100" + chatId : "-" + chatId;
        }
        return null;
    }

    private static int dc(int account, long userId, long chatId) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            if (userId != 0) {
                TLRPC.User user = mc.getUser(userId);
                if (user != null && user.photo != null) return user.photo.dc_id;
            } else if (chatId != 0) {
                TLRPC.Chat chat = mc.getChat(chatId);
                if (chat != null && chat.photo != null) return chat.photo.dc_id;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return 0;
    }

    public static void bind(TextDetailCell cell, int account, long userId, long chatId) {
        String id = idText(account, userId, chatId);
        int dc = dc(account, userId, chatId);
        cell.setTextAndValue(id != null ? id : "—", dc > 0 ? "ID · DC" + dc : "ID", false);
    }

    public static void copy(BaseFragment fragment, long userId, long chatId) {
        String id = idText(fragment.getCurrentAccount(), userId, chatId);
        if (id == null) return;
        try {
            AndroidUtilities.addToClipboard(id);
            BulletinFactory.of(fragment).createCopyBulletin("ID скопирован").show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
