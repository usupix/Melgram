package org.telegram.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** "Настройки Melgram": every Melgram feature on one screen. */
public class MelgramSettingsActivity extends Activity {

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
            new Option(MelgramConfig.HIDE_STORIES, "Скрыть истории", "Не показывать ленту историй над списком чатов", false),
            new Option(MelgramConfig.TIME_SECONDS, "Время с секундами", "У сообщений: 12:34:56", false),
            new Option(MelgramConfig.NO_JUMP_NEXT_CHANNEL, "Не переходить к следующему каналу", "Свайп вверх в конце канала больше не открывает следующий непрочитанный", false),
            new Option(MelgramConfig.ASK_BEFORE_CALL, "Спрашивать перед звонком", "Чтобы не позвонить случайно", false),
            new Option(MelgramConfig.SILENCE_NON_CONTACTS, "Без звука от незнакомых", "Сообщения от тех, кого нет в контактах, приходят тихо", false),
            new Option(MelgramConfig.NO_GREETING_STICKER, "Убрать стикер-приветствие", "В новом пустом чате", false),
            new Option(MelgramConfig.HIDE_KEYBOARD_ON_SCROLL, "Прятать клавиатуру при прокрутке", "Начинаешь листать чат — клавиатура скрывается", false),
            new Option(MelgramConfig.NO_NUMBER_ROUNDING, "Точные числа", "1234 просмотра вместо 1.2K", false),
    };

    private static final Option[] DESIGN_OPTIONS = {
            new Option(MelgramConfig.NO_APPBAR_SHADOW, "Без тени под верхней панелью", "Плоский заголовок экранов", true),
            new Option(MelgramConfig.NO_GOOEY_AVATAR, "Без «жидкой» анимации аватара", "Аватар в профиле раскрывается без эффекта капли", false),
            new Option(MelgramConfig.NO_MEDIA_PREVIEW, "Без миниатюр в списке чатов", "Не показывать превью фото и видео у последнего сообщения", false),
            new Option(MelgramConfig.NO_PREDICTIVE_BACK, "Классическая анимация «назад»", "Отключить предпросмотр жеста «назад» на Android 14+", true),
    };

    private final List<Switch> optionSwitches = new ArrayList<>();
    private final List<Option> optionList = new ArrayList<>();
    private Switch masterSwitch;
    private TextView tabletValue;
    private boolean updatingMaster;
    private boolean bulkChange;
    private boolean restartNeeded;

    private int colorBg, colorCard, colorText, colorSub, colorAccent, colorDivider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        colorBg = dark ? 0xFF0E1621 : 0xFFF0F2F5;
        colorCard = dark ? 0xFF17212B : 0xFFFFFFFF;
        colorText = dark ? 0xFFFFFFFF : 0xFF000000;
        colorSub = dark ? 0xFF8A9AA9 : 0xFF707579;
        colorAccent = 0xFF26A69A;
        colorDivider = dark ? 0x22FFFFFF : 0x14000000;

        getWindow().setStatusBarColor(colorBg);
        getWindow().setNavigationBarColor(colorBg);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tabletValue != null) {
            tabletValue.setText(tabletName(MelgramConfig.tabletMode()));
        }
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(16), dp(12), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Настройки Melgram");
        title.setTextColor(colorText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, dp(8), 0);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("Версия " + MelgramUpdater.versionText());
        version.setTextColor(colorSub);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        version.setPadding(dp(8), dp(2), dp(8), 0);
        root.addView(version);

        // tools
        LinearLayout tools = card(root, null);
        addAction(tools, "Иконка приложения", "Встроенные картинки или своя из галереи",
                v -> startActivity(new Intent(this, MelgramIconActivity.class)));
        addDivider(tools);
        addAction(tools, "Звук уведомления из файла", "Любое аудио или видео до 5 минут, вырезать фрагмент",
                v -> startActivity(new Intent(this, MelgramToneActivity.class)));
        addDivider(tools);
        addAction(tools, "Проверить обновления", "Новые сборки Melgram приходят сами",
                v -> MelgramUpdater.checkManual(this));

        // master switch
        LinearLayout master = card(root, null);
        masterSwitch = addSwitchRow(master, "Включить все улучшения", "Сразу все пункты ниже, кроме режима планшета", null, true);
        masterSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (updatingMaster) return;
            boolean restart = false;
            bulkChange = true;
            for (int i = 0; i < optionList.size(); i++) {
                Option o = optionList.get(i);
                if (!MelgramConfig.isAvailable(o.key)) continue;
                if (o.restart && MelgramConfig.get(o.key) != checked) restart = true;
                MelgramConfig.set(o.key, checked);
                optionSwitches.get(i).setChecked(checked);
            }
            bulkChange = false;
            if (restart) {
                restartNeeded = true;
                showRestartHint();
            }
        });

        LinearLayout chats = card(root, "Чаты");
        addOptions(chats, CHAT_OPTIONS);

        LinearLayout design = card(root, "Оформление");
        addOptions(design, DESIGN_OPTIONS);
        addDivider(design);
        LinearLayout tabletRow = addAction(design, "Режим планшета", null, v -> chooseTabletMode());
        tabletValue = new TextView(this);
        tabletValue.setTextColor(colorAccent);
        tabletValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tabletValue.setText(tabletName(MelgramConfig.tabletMode()));
        tabletRow.addView(tabletValue);

        TextView note = new TextView(this);
        note.setText("Пункты с пометкой ↻ применятся после перезапуска Melgram. " +
                "Многие функции перенесены из открытого клиента Nekogram.");
        note.setTextColor(colorSub);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(dp(8), dp(12), dp(8), 0);
        root.addView(note);

        updateMaster();
        return scroll;
    }

    private void addOptions(LinearLayout card, Option[] options) {
        for (int i = 0; i < options.length; i++) {
            Option o = options[i];
            if (i > 0) addDivider(card);
            boolean available = MelgramConfig.isAvailable(o.key);
            String sub = available ? o.subtitle : "Недоступно в этой версии Telegram — вернётся после обновления Melgram";
            Switch sw = addSwitchRow(card, o.title + (o.restart ? "  ↻" : ""), sub, o, available);
            optionSwitches.add(sw);
            optionList.add(o);
        }
    }

    private Switch addSwitchRow(LinearLayout card, String title, String subtitle, Option option, boolean enabled) {
        LinearLayout row = row(card);
        LinearLayout texts = texts(row, title, subtitle);
        texts.setAlpha(enabled ? 1f : 0.45f);
        Switch sw = new Switch(this);
        sw.setEnabled(enabled);
        if (option != null) {
            sw.setChecked(enabled && MelgramConfig.get(option.key));
            sw.setOnCheckedChangeListener((b, checked) -> {
                if (bulkChange) return;
                MelgramConfig.set(option.key, checked);
                if (option.restart) {
                    restartNeeded = true;
                    showRestartHint();
                }
                updateMaster();
            });
        }
        row.addView(sw);
        if (enabled) {
            row.setOnClickListener(v -> sw.toggle());
        }
        return sw;
    }

    private LinearLayout addAction(LinearLayout card, String title, String subtitle, View.OnClickListener l) {
        LinearLayout row = row(card);
        texts(row, title, subtitle);
        row.setOnClickListener(l);
        return row;
    }

    private void updateMaster() {
        boolean all = true;
        boolean any = false;
        for (Option o : optionList) {
            if (!MelgramConfig.isAvailable(o.key)) continue;
            any = true;
            if (!MelgramConfig.get(o.key)) {
                all = false;
            }
        }
        updatingMaster = true;
        masterSwitch.setChecked(any && all);
        updatingMaster = false;
    }

    private void chooseTabletMode() {
        String[] items = {tabletName(MelgramConfig.TABLET_AUTO), tabletName(MelgramConfig.TABLET_ON), tabletName(MelgramConfig.TABLET_OFF)};
        new AlertDialog.Builder(this)
                .setTitle("Режим планшета ↻")
                .setSingleChoiceItems(items, MelgramConfig.tabletMode(), (d, which) -> {
                    if (which != MelgramConfig.tabletMode()) {
                        MelgramConfig.setInt(MelgramConfig.TABLET_MODE, which);
                        restartNeeded = true;
                        showRestartHint();
                    }
                    tabletValue.setText(tabletName(which));
                    d.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String tabletName(int mode) {
        if (mode == MelgramConfig.TABLET_ON) return "Включён";
        if (mode == MelgramConfig.TABLET_OFF) return "Выключен";
        return "Авто";
    }

    private void showRestartHint() {
        Toast.makeText(this, "Изменение применится после перезапуска Melgram", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (restartNeeded) {
            restartNeeded = false;
            new AlertDialog.Builder(this)
                    .setTitle("Перезапустить Melgram?")
                    .setMessage("Часть изменений применится только после перезапуска.")
                    .setPositiveButton("Перезапустить", (d, w) -> restartApp())
                    .setNegativeButton("Потом", (d, w) -> finish())
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ---------------------------------------------------------------- ui helpers

    private LinearLayout card(LinearLayout root, String header) {
        if (header != null) {
            TextView h = new TextView(this);
            h.setText(header);
            h.setTextColor(colorAccent);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(dp(8), dp(18), dp(8), dp(6));
            root.addView(h);
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = header == null ? dp(14) : 0;
        root.addView(card, lp);
        return card;
    }

    private LinearLayout row(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(colorDivider), null, null));
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout texts(LinearLayout row, String title, String subtitle) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(colorText);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(colorSub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(12);
        row.addView(texts, lp);
        return texts;
    }

    private void addDivider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(colorDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(16);
        card.addView(d, lp);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
