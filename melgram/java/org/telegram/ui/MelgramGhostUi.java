package org.telegram.ui;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramGhost;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;

/** Melgram: quick ghost-mode switch and "read for real" from menus. */
public final class MelgramGhostUi {

    /** Menu item id in a chat's "⋮" menu. */
    public static final int MENU_READ = 9201;

    private MelgramGhostUi() {
    }

    /** Adds the ghost switch to the "⋮" menu of the chat list and to the account menu. */
    public static void addMenuItem(BaseFragment fragment, ItemOptions options) {
        try {
            boolean on = MelgramConfig.ghostAny();
            options.add(R.drawable.ghost, on ? "Выключить режим призрака" : "Включить режим призрака", () -> toggle(fragment));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void toggle(BaseFragment fragment) {
        boolean value = !MelgramConfig.ghostAny();
        for (String key : MelgramConfig.GHOST_KEYS) {
            MelgramConfig.set(key, value);
        }
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done,
                    value ? "Режим призрака включён" : "Режим призрака выключен",
                    value ? "Без «прочитано», «печатает» и «в сети»" : "Telegram снова видит, что ты в сети и читаешь").show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void readForReal(BaseFragment fragment, long dialogId) {
        boolean sent = MelgramGhost.readForReal(fragment.getCurrentAccount(), dialogId);
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(sent ? R.raw.done : R.raw.error,
                    sent ? "Собеседник увидит, что сообщения прочитаны" : "Не получилось отметить прочитанным").show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
