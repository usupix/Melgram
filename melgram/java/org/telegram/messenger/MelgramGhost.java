package org.telegram.messenger;

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
            TLObject fake = null;
            if (MelgramConfig.ghost(MelgramConfig.GHOST_READ)) {
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
