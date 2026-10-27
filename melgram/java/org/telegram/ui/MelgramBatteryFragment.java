package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MelgramBattery;
import org.telegram.messenger.MelgramConfig;
import org.telegram.messenger.MelgramQuietHours;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

/** Melgram: what keeps Melgram awake, per account, and how to calm it down. */
public class MelgramBatteryFragment extends UniversalFragment {

    private static final int ID_LEVEL = 1;
    private static final int ID_BG_CONNECTION = 2;
    private static final int ID_KEEP_ALIVE = 3;
    private static final int ID_OPTIMIZATION = 4;
    private static final int ID_LOWBAT = 5;
    private static final int ID_LITE = 6;
    private static final int ID_REFRESH = 7;
    private static final int ID_LOWBAT_LEVEL = 50;
    private static final int ID_ACCOUNT = 100;
    private static final int[] LEVELS = {15, 20, 30, 50};

    @Override
    protected CharSequence getTitle() {
        return "Батарея";
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int level = MelgramBattery.batteryLevel();
        boolean charging = MelgramBattery.isCharging();
        boolean bg = MelgramBattery.backgroundConnection();
        boolean keepAlive = MelgramBattery.keepAliveService();
        boolean exempt = MelgramBattery.isIgnoringOptimizations();

        items.add(UItem.asHeader("Сейчас"));
        items.add(UItem.asButton(ID_LEVEL, R.drawable.msg2_battery, "Заряд",
                level >= 0 ? level + "%" + (charging ? " · заряжается" : "") : "—"));
        items.add(UItem.asButton(ID_BG_CONNECTION, R.drawable.msg_notifications, "Фоновое соединение", bg ? "Включено" : "Выключено"));
        items.add(UItem.asButton(ID_KEEP_ALIVE, R.drawable.msg_retry, "Служба поддержки соединения", keepAlive ? "Включена" : "Выключена"));
        items.add(UItem.asButton(ID_OPTIMIZATION, R.drawable.msg_settings, "Оптимизация Android", exempt ? "Без ограничений" : "Ограничивает"));
        items.add(UItem.asShadow(
                "Больше всего батареи уходит на фоновое соединение: Melgram держит связь с Telegram, когда свёрнут, " +
                "и просыпается на каждое новое сообщение. Служба поддержки перезапускает Melgram, если система его закрыла. " +
                (exempt ? "Android сейчас не ограничивает Melgram в фоне — уведомления надёжнее, но расход выше. "
                        : "Android может усыплять Melgram в фоне — батарея целее, но уведомления могут опаздывать. ") +
                "Нажми на пункт, чтобы изменить."));

        items.add(UItem.asHeader("Кто будит телефон сегодня"));
        long total = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) continue;
            TLRPC.User user = config.getCurrentUser();
            String name = user != null ? UserObject.getUserName(user) : "Аккаунт " + (a + 1);
            long background = MelgramBattery.backgroundToday(a);
            long foreground = MelgramBattery.foregroundToday(a);
            total += background;
            boolean on = !MelgramConfig.backgroundDisabled(a);
            String sub = String.format(Locale.US, "%s в фоне · %s при открытом приложении", plural(background), plural(foreground));
            if (!on) {
                sub += " · фон выключен";
            } else if (MelgramConfig.inQuietHours(a)) {
                sub += " · сейчас тихие часы";
            }
            items.add(UItem.asButtonCheck(ID_ACCOUNT + a, name, sub).setChecked(bg && on));
        }
        items.add(UItem.asButton(ID_REFRESH, R.drawable.msg_retry, "Обновить цифры").accent());
        items.add(UItem.asShadow(
                "Каждое обновление в фоне — новое сообщение, реакция, правка или «печатает» — будит телефон. " +
                (total > 2000 ? "Сегодня их много: обычно виноваты большие активные группы. " : "") +
                "Выключи фоновое соединение у аккаунта, которому не нужны мгновенные уведомления, " +
                "или включи для него тихие часы. Выход из лишних больших групп тоже помогает: " +
                "отключённый звук не мешает обновлениям приходить. Считается с момента обновления Melgram."));

        boolean low = MelgramConfig.get(MelgramConfig.LOWBAT_ENABLED);
        items.add(UItem.asHeader("Экономия"));
        items.add(UItem.asButtonCheck(ID_LOWBAT, "Экономия при низком заряде",
                "Ниже " + MelgramConfig.lowBatteryLevel() + "% без зарядки — фоновое соединение выключается у всех аккаунтов").setChecked(low));
        if (low) {
            for (int i = 0; i < LEVELS.length; i++) {
                items.add(UItem.asRadio(ID_LOWBAT_LEVEL + i, "Ниже " + LEVELS[i] + "%").setChecked(MelgramConfig.lowBatteryLevel() == LEVELS[i]));
            }
        }
        items.add(UItem.asButton(ID_LITE, R.drawable.msg2_battery, "Энергосбережение Telegram"));
        items.add(UItem.asShadow(
                "«Энергосбережение Telegram» отключает тяжёлые анимации, автовоспроизведение видео и эффекты — " +
                "это тратит батарею, когда Melgram открыт. Тихие часы — в «Настройки Melgram»."));
    }

    private static String plural(long n) {
        long mod10 = n % 10, mod100 = n % 100;
        String word;
        if (mod10 == 1 && mod100 != 11) word = "обновление";
        else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) word = "обновления";
        else word = "обновлений";
        return MelgramConfig.formatExact(n) + " " + word;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        if (item.id == ID_BG_CONNECTION || item.id == ID_KEEP_ALIVE) {
            presentFragment(new NotificationsSettingsActivity());
        } else if (item.id == ID_OPTIMIZATION) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable e) {
                FileLog.e(e);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Не получилось открыть настройки Android").show();
            }
        } else if (item.id == ID_LITE) {
            presentFragment(new LiteModeSettingsActivity());
        } else if (item.id == ID_REFRESH || item.id == ID_LEVEL) {
            listView.adapter.update(true);
        } else if (item.id == ID_LOWBAT) {
            boolean value = !MelgramConfig.get(MelgramConfig.LOWBAT_ENABLED);
            MelgramConfig.set(MelgramConfig.LOWBAT_ENABLED, value);
            if (view instanceof NotificationsCheckCell) ((NotificationsCheckCell) view).setChecked(value);
            MelgramBattery.refreshLowBattery();
            MelgramQuietHours.ensureTicker();
            MelgramQuietHours.applyAll(true);
            listView.adapter.update(true);
        } else if (item.id >= ID_LOWBAT_LEVEL && item.id < ID_LOWBAT_LEVEL + LEVELS.length) {
            MelgramConfig.setInt(MelgramConfig.LOWBAT_LEVEL, LEVELS[item.id - ID_LOWBAT_LEVEL]);
            MelgramBattery.refreshLowBattery();
            MelgramQuietHours.applyAll(true);
            listView.adapter.update(true);
        } else if (item.id >= ID_ACCOUNT && item.id < ID_ACCOUNT + UserConfig.MAX_ACCOUNT_COUNT) {
            int account = item.id - ID_ACCOUNT;
            if (!MelgramBattery.backgroundConnection()) {
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
            if (view instanceof NotificationsCheckCell) ((NotificationsCheckCell) view).setChecked(!disable);
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
