package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramQuietHours;
import org.telegram.messenger.MelgramUpdater;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** "Настройки Melgram", built from Telegram's own settings cells. */
public class MelgramSettingsFragment extends UniversalFragment {

    private static final int ID_MASTER = 1;
    private static final int ID_ICON = 2;
    private static final int ID_TONE = 3;
    private static final int ID_UPDATE = 4;
    private static final int ID_TABLET = 100;
    private static final int ID_BACKGROUND = 200;
    private static final int ID_OPTION = 1000;
    private static final int ID_GHOST_ALL = 300;
    private static final int ID_GHOST = 310;
    private static final int ID_GHOST_AFTER_SEND = 320;
    private static final int ID_QUIET = 400;
    private static final int ID_QUIET_FROM = 401;
    private static final int ID_QUIET_TO = 402;
    private static final int ID_QUIET_ACCOUNT = 410;
    private static final int ID_TAP_IN = 500;
    private static final int ID_TAP_OUT = 501;

    private static final String[] GHOST_TITLES = {"Не отправлять «прочитано»", "Не показывать «печатает…»", "Всегда «не в сети»", "Смотреть истории незаметно"};
    private static final String[] GHOST_SUBTITLES = {
            "Собеседник не увидит, что ты прочитал сообщение",
            "И «записывает голосовое», «отправляет фото»",
            "Не отмечаться в сети, пока Melgram открыт",
            "Автор не увидит тебя в списке просмотров"};
    private static final String[] TAP_NAMES = {"Реакция", "Ответить", "Копировать текст", "Редактировать", "Ничего"};

    private static final class Option {
        final String key;
        final String title;
        final String subtitle;
        final boolean restart;

        Option(String key, String title, String subtitle, boolean restart) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.restart = restart;
        }
    }

    private static final Option[] CHAT_OPTIONS = {
            new Option(MelgramConfig.SCROLL_TOP_BUTTON, "Кнопка «Наверх»", "Появляется, когда пролистал список чатов вниз", true),
            new Option(MelgramConfig.HIDE_STORIES, "Скрыть истории", "Без ленты историй над списком чатов", false),
            new Option(MelgramConfig.TIME_SECONDS, "Время с секундами", "У сообщений и в списке чатов: 12:34:56", false),
            new Option(MelgramConfig.NO_JUMP_NEXT_CHANNEL, "Не переходить к следующему каналу", "Свайп в конце канала не открывает следующий", false),
            new Option(MelgramConfig.ASK_BEFORE_CALL, "Спрашивать перед звонком", "Чтобы не позвонить случайно", false),
            new Option(MelgramConfig.SILENCE_NON_CONTACTS, "Без звука от незнакомых", "Сообщения не из контактов приходят тихо", false),
            new Option(MelgramConfig.NO_GREETING_STICKER, "Убрать стикер-приветствие", "В новом пустом чате", false),
            new Option(MelgramConfig.HIDE_KEYBOARD_ON_SCROLL, "Прятать клавиатуру", "Когда начинаешь листать чат", false),
            new Option(MelgramConfig.NO_NUMBER_ROUNDING, "Точные числа", "34,355 просмотров вместо 34K", false),
    };

    private static final Option[] DESIGN_OPTIONS = {
            new Option(MelgramConfig.NO_APPBAR_SHADOW, "Без тени под верхней панелью", "Плоский заголовок экранов", true),
            new Option(MelgramConfig.NO_GOOEY_AVATAR, "Без «жидкой» анимации аватара", "Аватар в профиле раскрывается без капли", false),
            new Option(MelgramConfig.NO_MEDIA_PREVIEW, "Без миниатюр в списке чатов", "Вместо превью фото и видео — значок 🖼", false),
    };

    private static Option option(int index) {
        return index < CHAT_OPTIONS.length ? CHAT_OPTIONS[index] : DESIGN_OPTIONS[index - CHAT_OPTIONS.length];
    }

    private static int optionCount() {
        return CHAT_OPTIONS.length + DESIGN_OPTIONS.length;
    }

    @Override
    protected CharSequence getTitle() {
        return "Melgram";
    }

    private static boolean allEnabled() {
        boolean any = false;
        for (int i = 0; i < optionCount(); i++) {
            Option o = option(i);
            if (!MelgramConfig.isAvailable(o.key)) continue;
            any = true;
            if (!MelgramConfig.get(o.key)) return false;
        }
        return any;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asRippleCheck(ID_MASTER, "Включить все улучшения").setChecked(allEnabled()));
        items.add(UItem.asShadow("Сразу все пункты из разделов «Чаты» и «Оформление». Многие функции перенесены из открытого клиента Nekogram."));

        items.add(UItem.asHeader("Melgram"));
        items.add(UItem.asButton(ID_ICON, R.drawable.msg_palette, "Иконка приложения"));
        items.add(UItem.asButton(ID_TONE, R.drawable.msg_tone_add, "Звук уведомления из файла"));
        items.add(UItem.asButton(ID_UPDATE, R.drawable.msg_retry, "Проверить обновления", MelgramUpdater.versionText()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Чаты"));
        for (int i = 0; i < CHAT_OPTIONS.length; i++) {
            items.add(optionItem(i));
        }
        if (MelgramConfig.isAvailable("double_tap")) {
            items.add(UItem.asButton(ID_TAP_IN, "Двойное нажатие: чужие", TAP_NAMES[MelgramConfig.doubleTapAction(false)]));
            items.add(UItem.asButton(ID_TAP_OUT, "Двойное нажатие: свои", TAP_NAMES[MelgramConfig.doubleTapAction(true)]));
        }
        items.add(UItem.asShadow(MelgramConfig.isAvailable("message_menu")
                ? "В меню сообщения (долгое нажатие) появились «Переслать без автора» и сохранение стикеров и кружков в галерею."
                : null));

        items.add(UItem.asHeader("Оформление"));
        for (int i = CHAT_OPTIONS.length; i < optionCount(); i++) {
            items.add(optionItem(i));
        }
        items.add(UItem.asShadow("Пункты с пометкой ↻ применяются после перезапуска Melgram."));

        if (MelgramConfig.isAvailable("ghost_mode")) {
            items.add(UItem.asHeader("Режим призрака"));
            items.add(UItem.asButtonCheck(ID_GHOST_ALL, "Режим призрака", "Все пункты ниже разом").setChecked(allGhost()));
            for (int i = 0; i < MelgramConfig.GHOST_KEYS.length; i++) {
                items.add(UItem.asCheck(ID_GHOST + i, GHOST_TITLES[i]).setChecked(MelgramConfig.ghost(MelgramConfig.GHOST_KEYS[i])));
            }
            items.add(UItem.asCheck(ID_GHOST_AFTER_SEND, "Прочитать после отправки").setChecked(MelgramConfig.get(MelgramConfig.GHOST_READ_AFTER_SEND)).setEnabled(MelgramConfig.ghost(MelgramConfig.GHOST_READ)));
            items.add(UItem.asShadow("«Прочитать после отправки»: когда отвечаешь в чате, он честно отмечается прочитанным — иначе собеседник видел бы ответ без прочтения. " +
                    "Прочитать любой чат по-настоящему можно в его меню «⋮» → «Прочитать для собеседника». " +
                    "Быстро включить или выключить призрака — в меню «⋮» на главном экране. " +
                    "«Не в сети» не скрывает время последнего визита — его настраивают в «Конфиденциальность» → «Время захода»."));
        }

        if (MelgramConfig.isAvailable("account_background")) {
            boolean global = globalBackgroundConnection();
            items.add(UItem.asHeader("Фоновое соединение"));
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig config = UserConfig.getInstance(a);
                if (!config.isClientActivated()) continue;
                TLRPC.User user = config.getCurrentUser();
                String name = user != null ? UserObject.getUserName(user) : "Аккаунт " + (a + 1);
                boolean on = !MelgramConfig.backgroundDisabled(a);
                String sub = !global ? "Выключено для всех в настройках уведомлений"
                        : on ? "Новые сообщения приходят в фоне" : "Только когда Melgram открыт";
                items.add(UItem.asButtonCheck(ID_BACKGROUND + a, name, sub).setChecked(global && on));
            }
            items.add(UItem.asShadow("Держит связь с Telegram, когда Melgram свёрнут, чтобы уведомления приходили сразу. " +
                    "Выключи у второстепенных аккаунтов, чтобы экономить батарею: их сообщения придут, когда откроешь Melgram. " +
                    "Общий переключатель — «Уведомления и звуки» → «Фоновое соединение»."));
        }

        if (MelgramConfig.isAvailable("quiet_hours")) {
            boolean quiet = MelgramConfig.quietEnabled();
            items.add(UItem.asHeader("Тихие часы"));
            items.add(UItem.asButtonCheck(ID_QUIET, "Тихие часы",
                    MelgramConfig.formatMinutes(MelgramConfig.quietFrom()) + " – " + MelgramConfig.formatMinutes(MelgramConfig.quietTo())).setChecked(quiet));
            if (quiet) {
                items.add(UItem.asButton(ID_QUIET_FROM, "Начало", MelgramConfig.formatMinutes(MelgramConfig.quietFrom())));
                items.add(UItem.asButton(ID_QUIET_TO, "Конец", MelgramConfig.formatMinutes(MelgramConfig.quietTo())));
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    UserConfig config = UserConfig.getInstance(a);
                    if (!config.isClientActivated()) continue;
                    TLRPC.User user = config.getCurrentUser();
                    String name = user != null ? UserObject.getUserName(user) : "Аккаунт " + (a + 1);
                    items.add(UItem.asCheck(ID_QUIET_ACCOUNT + a, name).setChecked(MelgramConfig.quietForAccount(a)));
                }
            }
            items.add(UItem.asShadow("В это время отмеченные аккаунты не держат фоновое соединение и не звенят. " +
                    "Сообщения не теряются — они придут, когда откроешь Melgram или закончатся тихие часы."));
        }

        if (MelgramConfig.isAvailable("tablet_mode")) {
            int mode = MelgramConfig.tabletMode();
            items.add(UItem.asHeader("Режим планшета ↻"));
            items.add(UItem.asRadio(ID_TABLET + MelgramConfig.TABLET_AUTO, "Автоматически").setChecked(mode == MelgramConfig.TABLET_AUTO));
            items.add(UItem.asRadio(ID_TABLET + MelgramConfig.TABLET_ON, "Включён").setChecked(mode == MelgramConfig.TABLET_ON));
            items.add(UItem.asRadio(ID_TABLET + MelgramConfig.TABLET_OFF, "Выключен").setChecked(mode == MelgramConfig.TABLET_OFF));
            items.add(UItem.asShadow("Двухпанельный интерфейс, как на планшете: список чатов слева, переписка справа."));
        }
    }

    private UItem optionItem(int index) {
        Option o = option(index);
        boolean available = MelgramConfig.isAvailable(o.key);
        String title = o.title + (o.restart ? " ↻" : "");
        String subtitle = available ? o.subtitle : "Недоступно в этой версии Telegram";
        return UItem.asButtonCheck(ID_OPTION + index, title, subtitle).setChecked(available && MelgramConfig.get(o.key));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_MASTER) {
            boolean value = !allEnabled();
            boolean restart = false;
            for (int i = 0; i < optionCount(); i++) {
                Option o = option(i);
                if (!MelgramConfig.isAvailable(o.key)) continue;
                if (o.restart && MelgramConfig.get(o.key) != value) restart = true;
                MelgramConfig.set(o.key, value);
            }
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            listView.adapter.update(true);
            if (restart) showRestartHint();
        } else if (item.id == ID_ICON) {
            presentFragment(new MelgramIconFragment());
        } else if (item.id == ID_TONE) {
            presentFragment(new MelgramToneFragment());
        } else if (item.id == ID_UPDATE) {
            MelgramUpdater.checkManual(getParentActivity());
        } else if (item.id == ID_GHOST_ALL) {
            boolean value = !allGhost();
            for (String k : MelgramConfig.GHOST_KEYS) MelgramConfig.set(k, value);
            if (view instanceof NotificationsCheckCell) ((NotificationsCheckCell) view).setChecked(value);
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, value ? "Режим призрака включён" : "Режим призрака выключен").show();
        } else if (item.id >= ID_GHOST && item.id < ID_GHOST + MelgramConfig.GHOST_KEYS.length) {
            String key = MelgramConfig.GHOST_KEYS[item.id - ID_GHOST];
            boolean value = !MelgramConfig.ghost(key);
            MelgramConfig.set(key, value);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(value);
            listView.adapter.update(true);
        } else if (item.id == ID_GHOST_AFTER_SEND) {
            if (!MelgramConfig.ghost(MelgramConfig.GHOST_READ)) return;
            boolean value = !MelgramConfig.get(MelgramConfig.GHOST_READ_AFTER_SEND);
            MelgramConfig.set(MelgramConfig.GHOST_READ_AFTER_SEND, value);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(value);
            listView.adapter.update(true);
        } else if (item.id == ID_TAP_IN || item.id == ID_TAP_OUT) {
            chooseDoubleTap(item.id == ID_TAP_OUT);
        } else if (item.id == ID_QUIET) {
            boolean value = !MelgramConfig.quietEnabled();
            MelgramConfig.set(MelgramConfig.QUIET_ENABLED, value);
            if (view instanceof NotificationsCheckCell) ((NotificationsCheckCell) view).setChecked(value);
            MelgramQuietHours.applyAll(true);
            if (value) MelgramQuietHours.ensureTicker();
            listView.adapter.update(true);
        } else if (item.id == ID_QUIET_FROM || item.id == ID_QUIET_TO) {
            chooseTime(item.id == ID_QUIET_FROM);
        } else if (item.id >= ID_QUIET_ACCOUNT && item.id < ID_QUIET_ACCOUNT + UserConfig.MAX_ACCOUNT_COUNT) {
            int account = item.id - ID_QUIET_ACCOUNT;
            boolean value = !MelgramConfig.quietForAccount(account);
            MelgramConfig.setQuietForAccount(account, value);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(value);
            MelgramQuietHours.applyAll(true);
            listView.adapter.update(true);
        } else if (item.id >= ID_BACKGROUND && item.id < ID_BACKGROUND + UserConfig.MAX_ACCOUNT_COUNT) {
            int account = item.id - ID_BACKGROUND;
            if (!globalBackgroundConnection()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Сначала включи «Фоновое соединение» в настройках уведомлений").show();
                return;
            }
            boolean disable = !MelgramConfig.backgroundDisabled(account);
            MelgramConfig.setBackgroundDisabled(account, disable);
            try {
                ConnectionsManager cm = ConnectionsManager.getInstance(account);
                cm.setPushConnectionEnabled(cm.isPushConnectionEnabled());
            } catch (Throwable ignore) {
            }
            if (view instanceof NotificationsCheckCell) {
                ((NotificationsCheckCell) view).setChecked(!disable);
            }
            listView.adapter.update(true);
        } else if (item.id >= ID_TABLET && item.id <= ID_TABLET + MelgramConfig.TABLET_OFF) {
            int mode = item.id - ID_TABLET;
            if (mode != MelgramConfig.tabletMode()) {
                MelgramConfig.setInt(MelgramConfig.TABLET_MODE, mode);
                showRestartHint();
            }
            listView.adapter.update(true);
        } else if (item.id >= ID_OPTION && item.id < ID_OPTION + optionCount()) {
            Option o = option(item.id - ID_OPTION);
            if (!MelgramConfig.isAvailable(o.key)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Эта функция вернётся после обновления Melgram").show();
                return;
            }
            boolean value = !MelgramConfig.get(o.key);
            MelgramConfig.set(o.key, value);
            if (view instanceof NotificationsCheckCell) {
                ((NotificationsCheckCell) view).setChecked(value);
            }
            listView.adapter.update(true);
            if (o.restart) showRestartHint();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private static boolean allGhost() {
        for (String k : MelgramConfig.GHOST_KEYS) {
            if (!MelgramConfig.ghost(k)) return false;
        }
        return true;
    }

    private void chooseDoubleTap(boolean outgoing) {
        if (getParentActivity() == null) return;
        final int[] values = outgoing
                ? new int[]{MelgramConfig.TAP_REACTION, MelgramConfig.TAP_REPLY, MelgramConfig.TAP_COPY, MelgramConfig.TAP_EDIT, MelgramConfig.TAP_NONE}
                : new int[]{MelgramConfig.TAP_REACTION, MelgramConfig.TAP_REPLY, MelgramConfig.TAP_COPY, MelgramConfig.TAP_NONE};
        CharSequence[] names = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) names[i] = TAP_NAMES[values[i]];
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(outgoing ? "Двойное нажатие: свои сообщения" : "Двойное нажатие: чужие сообщения");
        builder.setItems(names, (dialog, which) -> {
            MelgramConfig.setInt(outgoing ? MelgramConfig.TAP_OUT : MelgramConfig.TAP_IN, values[which]);
            listView.adapter.update(true);
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void chooseTime(boolean start) {
        if (getParentActivity() == null) return;
        int current = start ? MelgramConfig.quietFrom() : MelgramConfig.quietTo();
        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        final NumberPicker hours = new NumberPicker(getParentActivity());
        hours.setMinValue(0);
        hours.setMaxValue(23);
        hours.setValue(current / 60);
        hours.setFormatter(value -> String.format(java.util.Locale.US, "%02d", value));
        hours.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        final NumberPicker minutes = new NumberPicker(getParentActivity());
        minutes.setMinValue(0);
        minutes.setMaxValue(11);
        minutes.setValue((current % 60) / 5);
        minutes.setFormatter(value -> String.format(java.util.Locale.US, "%02d", value * 5));
        minutes.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(hours, LayoutHelper.createLinear(64, 160));
        layout.addView(minutes, LayoutHelper.createLinear(64, 160, 16, 0, 0, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(start ? "Начало тихих часов" : "Конец тихих часов");
        builder.setView(layout);
        builder.setPositiveButton("Готово", (dialog, which) -> {
            MelgramConfig.setInt(start ? MelgramConfig.QUIET_FROM : MelgramConfig.QUIET_TO, hours.getValue() * 60 + minutes.getValue() * 5);
            MelgramQuietHours.applyAll(true);
            listView.adapter.update(true);
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    /** Telegram's own global "Background connection" setting (same rule as ConnectionsManager). */
    private static boolean globalBackgroundConnection() {
        try {
            SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
            if (preferences.contains("pushConnection")) {
                return preferences.getBoolean("pushConnection", true);
            }
            return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("backgroundConnection", false);
        } catch (Throwable e) {
            return true;
        }
    }

    private void showRestartHint() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Изменение применится после перезапуска", "Перезапустить", this::restartApp).show();
    }

    private void restartApp() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
        }
        activity.finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
