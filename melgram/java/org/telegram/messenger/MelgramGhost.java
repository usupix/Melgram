package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

/**
 * Melgram ghost mode. Called by ConnectionsManager before every request:
 * read receipts, "typing…" and story views can be held back, and the online
 * status can be forced to "offline". Held-back requests get a harmless local
 * answer so Telegram's own code keeps working as if the server had replied.
 */
public final class MelgramGhost {

    private static final long ALLOW_MS = 60_000;
    // "account:dialog" -> time until which real read receipts are allowed
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> allowedReads = new java.util.concurrent.ConcurrentHashMap<>();

    private MelgramGhost() {
    }

    /** @return true when the request must not be sent */
    public static boolean intercept(int account, TLObject req, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        try {
            if (req == null) {
                return false;
            }
            if (req instanceof TL_account.updateStatus) {
                if (MelgramConfig.ghost(MelgramConfig.GHOST_OFFLINE)) {
                    ((TL_account.updateStatus) req).offline = true;
                }
                return false;
            }
            if (MelgramConfig.ghost(MelgramConfig.GHOST_READ) && MelgramConfig.get(MelgramConfig.GHOST_READ_AFTER_SEND)) {
                long sentTo = sendTarget(req);
                if (sentTo != 0) {
                    scheduleReadAfterSend(account, sentTo, 0);
                }
            }
            TLObject fake = null;
            if (MelgramConfig.ghost(MelgramConfig.GHOST_READ) && !isAllowedRead(account, req)) {
                if (req instanceof TLRPC.TL_messages_readHistory || req instanceof TLRPC.TL_messages_readMessageContents) {
                    TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
                    res.pts = -1;
                    res.pts_count = 0;
                    fake = res;
                } else if (req instanceof TLRPC.TL_channels_readHistory
                        || req instanceof TLRPC.TL_channels_readMessageContents
                        || req instanceof TLRPC.TL_messages_readEncryptedHistory
                        || req instanceof TLRPC.TL_messages_readDiscussion) {
                    fake = new TLRPC.TL_boolTrue();
                }
            }
            if (fake == null && MelgramConfig.ghost(MelgramConfig.GHOST_TYPING)) {
                if (req instanceof TLRPC.TL_messages_setTyping || req instanceof TLRPC.TL_messages_setEncryptedTyping) {
                    fake = new TLRPC.TL_boolTrue();
                }
            }
            if (fake == null && MelgramConfig.ghost(MelgramConfig.GHOST_STORIES)) {
                if (req instanceof TL_stories.TL_stories_readStories) {
                    // Telegram never waits for this answer; if some code does, let the request through
                    return onComplete == null && onCompleteTimestamp == null;
                } else if (req instanceof TL_stories.TL_stories_incrementStoryViews) {
                    fake = new TLRPC.TL_boolTrue();
                }
            }
            if (fake == null) {
                return false;
            }
            return answer(fake, onComplete, onCompleteTimestamp);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------- real reads

    private static long dialogIdOf(int account, TLRPC.InputPeer peer) {
        if (peer == null) return 0;
        if (peer instanceof TLRPC.TL_inputPeerSelf) return UserConfig.getInstance(account).getClientUserId();
        if (peer.user_id != 0) return peer.user_id;
        if (peer.chat_id != 0) return -peer.chat_id;
        if (peer.channel_id != 0) return -peer.channel_id;
        return 0;
    }

    private static long sendTarget(TLObject req) {
        TLRPC.InputPeer peer = null;
        if (req instanceof TLRPC.TL_messages_sendMessage) peer = ((TLRPC.TL_messages_sendMessage) req).peer;
        else if (req instanceof TLRPC.TL_messages_sendMedia) peer = ((TLRPC.TL_messages_sendMedia) req).peer;
        else if (req instanceof TLRPC.TL_messages_sendMultiMedia) peer = ((TLRPC.TL_messages_sendMultiMedia) req).peer;
        else if (req instanceof TLRPC.TL_messages_forwardMessages) peer = ((TLRPC.TL_messages_forwardMessages) req).to_peer;
        if (peer == null || peer instanceof TLRPC.TL_inputPeerSelf) return 0;
        if (peer.user_id != 0) return peer.user_id;
        if (peer.chat_id != 0) return -peer.chat_id;
        if (peer.channel_id != 0) return -peer.channel_id;
        return 0;
    }

    private static boolean isAllowedRead(int account, TLObject req) {
        long did = 0;
        if (req instanceof TLRPC.TL_messages_readHistory) did = dialogIdOf(account, ((TLRPC.TL_messages_readHistory) req).peer);
        else if (req instanceof TLRPC.TL_messages_readDiscussion) did = dialogIdOf(account, ((TLRPC.TL_messages_readDiscussion) req).peer);
        else if (req instanceof TLRPC.TL_channels_readHistory && ((TLRPC.TL_channels_readHistory) req).channel != null) did = -((TLRPC.TL_channels_readHistory) req).channel.channel_id;
        else if (req instanceof TLRPC.TL_messages_readEncryptedHistory && ((TLRPC.TL_messages_readEncryptedHistory) req).peer != null) did = DialogObject.makeEncryptedDialogId(((TLRPC.TL_messages_readEncryptedHistory) req).peer.chat_id);
        if (did == 0) return false;
        Long until = allowedReads.get(account + ":" + did);
        return until != null && until > System.currentTimeMillis();
    }

    public static void allowRead(int account, long dialogId) {
        allowedReads.put(account + ":" + dialogId, System.currentTimeMillis() + ALLOW_MS);
    }

    private static void scheduleReadAfterSend(int account, long dialogId, int attempt) {
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
            if ((dialog == null || dialog.top_message <= 0) && attempt < 3) {
                scheduleReadAfterSend(account, dialogId, attempt + 1);
                return;
            }
            readForReal(account, dialogId);
        }, attempt == 0 ? 2500 : 2000);
    }

    /** Sends a real read receipt for the dialog up to its newest message, even in ghost mode. */
    public static boolean readForReal(int account, long dialogId) {
        try {
            if (DialogObject.isEncryptedDialog(dialogId)) {
                return false;
            }
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.Dialog dialog = mc.dialogs_dict.get(dialogId);
            int maxId = dialog != null ? dialog.top_message : 0;
            TLObject req;
            if (dialogId < 0 && ChatObject.isChannel(mc.getChat(-dialogId))) {
                if (maxId <= 0) return false;
                TLRPC.TL_channels_readHistory r = new TLRPC.TL_channels_readHistory();
                r.channel = mc.getInputChannel(-dialogId);
                r.max_id = maxId;
                if (r.channel == null) return false;
                req = r;
            } else {
                TLRPC.TL_messages_readHistory r = new TLRPC.TL_messages_readHistory();
                r.peer = mc.getInputPeer(dialogId);
                r.max_id = Math.max(0, maxId);
                if (r.peer == null) return false;
                req = r;
            }
            allowRead(account, dialogId);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.TL_messages_affectedMessages) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    mc.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
            });
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean answer(TLObject fake, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        if (onComplete == null && onCompleteTimestamp == null) {
            return true;
        }
        Utilities.stageQueue.postRunnable(() -> {
            try {
                if (onComplete != null) {
                    onComplete.run(fake, null);
                } else {
                    onCompleteTimestamp.run(fake, null, System.currentTimeMillis());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
        return true;
    }
}
