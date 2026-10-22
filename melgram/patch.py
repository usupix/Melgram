#!/usr/bin/env python3
"""
Turns the official Telegram Android source tree into Melgram.

Usage:  python3 patch.py <path-to-telegram-source>

Settings come from environment variables:
  MELGRAM_API_ID, MELGRAM_API_HASH  - your keys from my.telegram.org (required)
  MELGRAM_ACCOUNTS                  - how many accounts (default 6)
  MELGRAM_PACKAGE                   - application id (default com.melgram.app)
  MELGRAM_NAME                      - app name (default Melgram)
  MELGRAM_ABIS                      - comma separated ABIs (default arm64-v8a)

Every edit checks that it actually applied, so if Telegram changes its code
the build stops with a clear message instead of producing a broken APK.
"""
import glob
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")

API_ID = os.environ.get("MELGRAM_API_ID", "").strip()
API_HASH = os.environ.get("MELGRAM_API_HASH", "").strip()
ACCOUNTS = int(os.environ.get("MELGRAM_ACCOUNTS", "6") or "6")
PKG = (os.environ.get("MELGRAM_PACKAGE", "") or "com.melgram.app").strip()
NAME = (os.environ.get("MELGRAM_NAME", "") or "Melgram").strip()
ABIS = [a.strip() for a in (os.environ.get("MELGRAM_ABIS", "") or "arm64-v8a").split(",") if a.strip()]
BUILD = int(os.environ.get("MELGRAM_BUILD", "0") or "0")
REPO = (os.environ.get("MELGRAM_REPO", "") or "").strip()
CONTACT = (os.environ.get("MELGRAM_CONTACT", "") or "miku_gf").strip().lstrip("@")

MAIN = os.path.join(SRC, "TMessagesProj", "src", "main")
JAVA = os.path.join(MAIN, "java")
RES = os.path.join(MAIN, "res")


def fail(msg):
    print("\n[melgram] ОШИБКА: " + msg, file=sys.stderr)
    sys.exit(1)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def rx(path, pattern, repl, min_count=1, flags=0, label=None):
    """Regex replace in a file; fails if fewer than min_count matches."""
    full = os.path.join(SRC, path) if not os.path.isabs(path) else path
    if not os.path.exists(full):
        fail("не найден файл " + full)
    text = read(full)
    new, n = re.subn(pattern, repl, text, flags=flags)
    if n < min_count:
        fail("не удалось применить правку '%s' в %s (найдено %d из %d)" % (label or pattern, path, n, min_count))
    write(full, new)
    print("[melgram] %-55s %s (%d)" % (label or "edit", os.path.relpath(full, SRC), n))
    return n


def check_inputs():
    if not os.path.isdir(MAIN):
        fail("это не исходники Telegram: " + SRC)
    if not API_ID.isdigit():
        fail("не задан MELGRAM_API_ID (число с my.telegram.org)")
    if not re.fullmatch(r"[0-9a-f]{32}", API_HASH):
        fail("MELGRAM_API_HASH должен быть строкой из 32 символов 0-9a-f с my.telegram.org")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", PKG):
        fail("неправильное имя пакета: " + PKG)
    if not 1 <= ACCOUNTS <= 32:
        fail("MELGRAM_ACCOUNTS должен быть от 1 до 32")
    if "telegram" in NAME.lower():
        fail("правила Telegram запрещают слово Telegram в названии форка")


# ------------------------------------------------------------------ accounts

def patch_accounts():
    uc = "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"
    rx(uc, r"MAX_ACCOUNT_DEFAULT_COUNT = \d+;", "MAX_ACCOUNT_DEFAULT_COUNT = %d;" % ACCOUNTS, label="аккаунты без Premium")
    rx(uc, r"MAX_ACCOUNT_COUNT = \d+;", "MAX_ACCOUNT_COUNT = %d;" % ACCOUNTS, label="максимум аккаунтов")
    rx(uc, r"return hasPremiumOnAccounts\(\) \? \d+ : \d+;", "return MAX_ACCOUNT_COUNT;", label="лимит не зависит от Premium")

    # "Add up to N accounts with Premium" hint under the account list
    rx("TMessagesProj/src/main/java/org/telegram/ui/UserInfoActivity.java",
       r"if \(!UserConfig\.hasPremiumOnAccounts\(\)\) \{(\s*final int moreAccounts)",
       r"if (false) {\1", label="убрать рекламу Premium про аккаунты")

    # Native network layer keeps one ConnectionsManager per account
    rx("TMessagesProj/jni/tgnet/Defines.h", r"#define MAX_ACCOUNT_COUNT \d+",
       "#define MAX_ACCOUNT_COUNT %d" % ACCOUNTS, label="аккаунты в нативном коде")

    cases = []
    for i in range(ACCOUNTS):
        head = "        case %d:\n" % i
        if i == ACCOUNTS - 1:
            head += "        default:\n"
        cases.append(head + "            static ConnectionsManager instance%d(%d);\n            return instance%d;\n" % (i, i, i))
    body = ("ConnectionsManager& ConnectionsManager::getInstance(int32_t instanceNum) {\n"
            "    switch (instanceNum) {\n" + "".join(cases) + "    }\n}\n")
    rx("TMessagesProj/jni/tgnet/ConnectionsManager.cpp",
       r"ConnectionsManager& ConnectionsManager::getInstance\(int32_t instanceNum\) \{.*?\n\}\n",
       lambda m: body, flags=re.S, label="отдельное соединение на каждый аккаунт")


# ------------------------------------------------------------------ identity

def patch_identity():
    bv = "TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java"
    rx(bv, r"public static int APP_ID = \d+;", "public static int APP_ID = %s;" % API_ID, label="api_id")
    rx(bv, r'public static String APP_HASH = "[0-9a-f]+";', 'public static String APP_HASH = "%s";' % API_HASH, label="api_hash")

    rx("gradle.properties", r"(?m)^APP_PACKAGE=.*$", "APP_PACKAGE=" + PKG, label="имя пакета")

    # google-services.json must list our package or the gradle plugin refuses to build.
    # The library module still looks up "org.telegram.messenger", so the original
    # clients are kept and copies with the Melgram package are added next to them.
    import json
    found = 0
    for gs in glob.glob(os.path.join(SRC, "**", "google-services.json"), recursive=True):
        data = json.loads(read(gs))
        clients = data.get("client", [])
        extra = []
        for c in clients:
            name = c["client_info"]["android_client_info"]["package_name"]
            if name.startswith("org.telegram.messenger"):
                copy = json.loads(json.dumps(c))
                copy["client_info"]["android_client_info"]["package_name"] = PKG + name[len("org.telegram.messenger"):]
                extra.append(copy)
        known = {c["client_info"]["android_client_info"]["package_name"] for c in clients}
        clients.extend(e for e in extra if e["client_info"]["android_client_info"]["package_name"] not in known)
        write(gs, json.dumps(data, indent=2))
        found += 1
        print("[melgram] %-55s %s (+%d)" % ("google-services.json", os.path.relpath(gs, SRC), len(extra)))
    if found == 0:
        fail("не найден google-services.json")

    # Only the main app module is built; the others need Huawei/HockeyApp setup
    rx("settings.gradle", r"(?m)^include ':TMessagesProj_App(Huawei|HockeyApp|Standalone|Tests)'\s*$\n?", "",
       min_count=1, label="убрать лишние модули сборки")

    # App name in every language
    names = 0
    for f in glob.glob(os.path.join(RES, "values*", "strings.xml")):
        text = read(f)
        new = re.sub(r'<string name="AppName">[^<]*</string>', '<string name="AppName">%s</string>' % NAME, text)
        new = re.sub(r'<string name="AppNameBeta">[^<]*</string>', '<string name="AppNameBeta">%s Beta</string>' % NAME, new)
        if new != text:
            write(f, new)
            names += 1
    if names == 0:
        fail("не нашёл строку AppName")
    print("[melgram] %-55s %d файлов" % ("название " + NAME, names))

    # Own Android account type and contact mime types, so Melgram and the
    # official Telegram can be installed side by side without conflicts
    rx("TMessagesProj/src/main/res/xml/auth.xml", r'android:accountType="org\.telegram\.messenger"',
       'android:accountType="%s"' % PKG, label="тип аккаунта Android")
    rx("TMessagesProj/src/main/res/xml/sync_contacts.xml", r'android:accountType="org\.telegram\.messenger"',
       'android:accountType="%s"' % PKG, label="синхронизация контактов")
    rx("TMessagesProj/src/main/res/xml/auth_menu.xml", r'android:targetPackage="org\.telegram\.messenger"',
       'android:targetPackage="%s"' % PKG, label="меню аккаунта")
    rx("TMessagesProj/src/main/java/org/telegram/messenger/ContactsController.java",
       r'"org\.telegram\.messenger"', '"%s"' % PKG, label="тип аккаунта в коде")
    mime_old = r"vnd\.org\.telegram\.messenger\.android"
    mime_new = "vnd.%s.android" % PKG
    rx("TMessagesProj/src/main/res/xml/contacts.xml", mime_old, mime_new, label="типы контактов")
    hits = 0
    for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        text = read(f)
        if "vnd.org.telegram.messenger.android" in text:
            write(f, text.replace("vnd.org.telegram.messenger.android", mime_new))
            hits += 1
    print("[melgram] %-55s %d файлов" % ("типы контактов в коде", hits))


# ------------------------------------------------------------------ build size

def patch_abis():
    abis = ", ".join('"%s"' % a for a in ABIS)
    rx("TMessagesProj_App/build.gradle",
       r"(\n\s*afat \{\s*ndk \{\s*abiFilters )[^\n]+",
       lambda m: m.group(1) + abis, label="архитектуры: " + ", ".join(ABIS))

    # ccache: repeat builds reuse already compiled C/C++ code
    if os.environ.get("MELGRAM_CCACHE") == "1":
        for gradle_file in ("TMessagesProj/build.gradle", "TMessagesProj_App/build.gradle"):
            rx(gradle_file, r"arguments '-DANDROID_STL=c\+\+_static'",
               "arguments '-DCMAKE_C_COMPILER_LAUNCHER=ccache', '-DCMAKE_CXX_COMPILER_LAUNCHER=ccache', '-DANDROID_STL=c++_static'",
               label="кэш компиляции C++")


# ------------------------------------------------------------------ icon

def copy_tree(src_dir, dst_dir):
    count = 0
    for root, _, files in os.walk(src_dir):
        rel = os.path.relpath(root, src_dir)
        target = os.path.join(dst_dir, rel)
        os.makedirs(target, exist_ok=True)
        for name in files:
            base = os.path.splitext(name)[0]
            # a resource may exist as .png and .webp - remove other variants
            for other in glob.glob(os.path.join(target, base + ".*")):
                if os.path.basename(other) != name:
                    os.remove(other)
            shutil.copy2(os.path.join(root, name), os.path.join(target, name))
            count += 1
    return count


def patch_icon():
    n = copy_tree(os.path.join(HERE, "res"), RES)
    print("[melgram] %-55s %d файлов" % ("иконка и ресурсы Melgram", n))
    n = copy_tree(os.path.join(HERE, "java"), JAVA)
    print("[melgram] %-55s %d файлов" % ("код Melgram", n))

    # Settings -> "Иконка Melgram", right after Language
    sa = "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"
    rx(sa,
       r"(\n(\s*)items\.add\(SettingCell\.Factory\.of\(10,[^\n]*SettingsLanguage[^\n]*\n)",
       lambda m: m.group(1) + m.group(2) +
       'items.add(SettingCell.Factory.of(1001, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, '
       'R.drawable.melgram_settings_icon, "Настройки Melgram", "Иконки, звуки, улучшения · " + org.telegram.messenger.MelgramUpdater.versionText()));\n',
       label="пункт в настройках")
    rx(sa,
       r"(\n(\s*)case 10:\s*\n\s*presentSettingFragment\(new LanguageSelectActivity\(\)\);\s*\n\s*break;)",
       lambda m: m.group(1) + "\n" + m.group(2) + "case 1001:\n" + m.group(2) +
       "    presentSettingFragment(new org.telegram.ui.MelgramSettingsFragment());\n" +
       m.group(2) + "    break;",
       label="открытие настроек Melgram")


# ------------------------------------------------------------------ updates

def patch_updates():
    props = read(os.path.join(SRC, "gradle.properties"))
    m = re.search(r"(?m)^APP_VERSION_NAME=(.+)$", props)
    if not m:
        fail("не нашёл APP_VERSION_NAME в gradle.properties")
    version = m.group(1).strip()
    url = "https://raw.githubusercontent.com/%s/main/update.json" % REPO if REPO else ""
    api_url = "https://api.github.com/repos/%s/contents/update.json?ref=main" % REPO if REPO else ""
    java = (
        "package org.telegram.messenger;\n\n"
        "/** Generated by Melgram patch.py */\n"
        "public final class MelgramBuild {\n"
        "    public static final int BUILD = %d;\n"
        "    public static final String VERSION = \"%s\";\n"
        "    public static final String UPDATE_URL = \"%s\";\n"
        "    public static final String UPDATE_API_URL = \"%s\";\n"
        "    public static final String CONTACT = \"%s\";\n"
        "    public static final String[] DISABLED_FEATURES = {%s};\n\n"
        "    private MelgramBuild() {\n    }\n"
        "}\n" % (BUILD, version, url, api_url, CONTACT, ", ".join('"%s"' % k for k in DISABLED)))
    write(os.path.join(JAVA, "org", "telegram", "messenger", "MelgramBuild.java"), java)
    print("[melgram] %-55s %s, сборка %d" % ("версия Melgram", version, BUILD))

    rx("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
       r"(protected void onResume\(\) \{\s*\n(\s*)super\.onResume\(\);)",
       lambda mm: mm.group(1) + "\n" + mm.group(2) +
       "try { org.telegram.messenger.MelgramUpdater.checkAuto(this); } catch (Throwable ignore) { }",
       label="проверка обновлений при запуске")



# ------------------------------------------------------------------ soft features
#
# Optional features (from Nekogram and Melgram's own). If a future Telegram
# version moves the code a feature hooks into, the feature is skipped instead
# of failing the build; the app then shows it as unavailable.

DISABLED = []
MC = "org.telegram.messenger.MelgramConfig"
J = "TMessagesProj/src/main/java/org/telegram/"


def soft_feature(key, label, edits):
    """edits: (path, pattern, replacement) - pattern must match exactly once;
    replacement None only checks that the pattern exists."""
    staged = {}
    for path, pattern, repl in edits:
        full = os.path.join(SRC, path)
        if full not in staged:
            if not os.path.exists(full):
                return _skip(key, label, "нет файла " + path)
            staged[full] = read(full)
        matches = len(re.findall(pattern, staged[full]))
        if matches != 1:
            return _skip(key, label, "место правки в %s найдено %d раз" % (os.path.basename(path), matches))
        if repl is not None:
            staged[full] = re.sub(pattern, repl, staged[full])
    for full, text in staged.items():
        write(full, text)
    print("[melgram] %-55s ok" % label)


def _skip(key, label, why):
    DISABLED.append(key)
    print("[melgram] %-55s ПРОПУЩЕНО: %s" % (label, why))
    print("::warning::Melgram: функция «%s» пропущена (%s)" % (label, why))


def after(code):
    return lambda m: m.group(0) + code


def patch_features():
    soft_feature("hide_stories", "скрыть истории", [
        (J + "ui/Stories/StoriesController.java", r"public boolean hasStories\(\) \{",
         after("\n        if (%s.hideStories()) return false;" % MC)),
    ])
    soft_feature("time_seconds", "время с секундами", [
        (J + "messenger/LocaleController.java", r"public FastDateFormat getFormatterDayWithSeconds\(\)", None),
        (J + "messenger/LocaleController.java", r"public FastDateFormat getFormatterDay\(\) \{",
         after("\n        if (%s.timeWithSeconds()) return getFormatterDayWithSeconds();" % MC)),
    ])
    soft_feature("no_number_rounding", "точные числа", [
        (J + "messenger/LocaleController.java", r"public static String formatShortNumber\(int number, int\[\] rounded\) \{",
         after("\n        if (%s.noNumberRounding()) { if (rounded != null) rounded[0] = number; return %s.formatExact(number); }" % (MC, MC))),
    ])
    soft_feature("no_jump_next_channel", "не переходить к следующему каналу", [
        (J + "ui/ChatActivity.java", r"final boolean allowPullingDownScroll = ",
         after("!%s.noJumpToNextChannel() && " % MC)),
    ])
    soft_feature("hide_keyboard_on_scroll", "прятать клавиатуру при прокрутке", [
        (J + "ui/ChatActivity.java", r"\} else if \(newState == RecyclerView\.SCROLL_STATE_DRAGGING\) \{(?=\s*\n\s*pollHintCell = null;)",
         after("\n                        if (%s.hideKeyboardOnScroll()) {\n"
               "                            if (isKeyboardVisible()) {\n"
               "                                AndroidUtilities.hideKeyboard(getParentActivity() != null ? getParentActivity().getCurrentFocus() : null);\n"
               "                            } else if (chatActivityEnterView != null) {\n"
               "                                chatActivityEnterView.hidePopup(true);\n"
               "                            }\n"
               "                        }" % MC)),
    ])
    soft_feature("ask_before_call", "спрашивать перед звонком", [
        (J + "ui/Components/AlertsCreator.java", r"public static void createCallDialogAlert\(BaseFragment fragment, TLRPC\.User user, boolean videoCall\)", None),
        (J + "ui/Components/voip/VoIPHelper.java",
         r"public static void startCall\(TLRPC\.User user, boolean videoCall, boolean canVideoCall, final Activity activity, TLRPC\.UserFull userFull, AccountInstance accountInstance\) \{",
         after("\n\t\tif (user != null && %s.shouldAskBeforeCall(user.id) && activity instanceof org.telegram.ui.LaunchActivity) {\n"
               "\t\t\torg.telegram.ui.ActionBar.BaseFragment melgramLast = ((org.telegram.ui.LaunchActivity) activity).getActionBarLayout().getLastFragment();\n"
               "\t\t\tif (melgramLast != null) {\n"
               "\t\t\t\t%s.markCallAsked(user.id);\n"
               "\t\t\t\torg.telegram.ui.Components.AlertsCreator.createCallDialogAlert(melgramLast, user, videoCall);\n"
               "\t\t\t\treturn;\n"
               "\t\t\t}\n"
               "\t\t}" % (MC, MC))),
    ])
    soft_feature("silence_non_contacts", "без звука от незнакомых", [
        (J + "messenger/NotificationsController.java", r"\n(\s*)if \(!notifyDisabled && dialog_id == override_dialog_id && chat != null\) \{",
         lambda m: "\n" + m.group(1) + "if (%s.silenceNonContacts() && chat == null && getContactsController().contactsDict.get(userId) == null) {\n" % MC
                   + m.group(1) + "    notifyDisabled = true;\n" + m.group(1) + "}" + m.group(0)),
    ])
    gv = J + "ui/Components/ChatGreetingsView.java"
    soft_feature("no_greeting_sticker", "убрать стикер-приветствие", [
        (gv, r"\n(\s*)boolean wasDraw;", lambda m: m.group(0) + "\n" + m.group(1) + "boolean showGreetings = !%s.noGreetingSticker();" % MC),
        (gv, r"addView\(titleView, LayoutHelper\.createLinear\(LayoutHelper\.WRAP_CONTENT, LayoutHelper\.WRAP_CONTENT, Gravity\.CENTER_HORIZONTAL, 20, 6, 20, 6\)\);",
         lambda m: "addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20, showGreetings || preview ? 6 : -2, 20, 6));"),
        (gv, r"if \(getMeasuredHeight\(\) > MeasureSpec\.getSize\(heightMeasureSpec\) && !preview\) \{",
         lambda m: "if ((!showGreetings || getMeasuredHeight() > MeasureSpec.getSize(heightMeasureSpec)) && !preview) {"),
    ])
    soft_feature("no_appbar_shadow", "без тени под верхней панелью", [
        (J + "ui/ActionBar/ActionBarLayout.java", r"headerShadowDrawable = getResources\(\)\.getDrawable\(R\.drawable\.header_shadow\)\.mutate\(\);",
         lambda m: "headerShadowDrawable = %s.noAppBarShadow() ? null : getResources().getDrawable(R.drawable.header_shadow).mutate();" % MC),
    ])
    soft_feature("no_gooey_avatar", "без жидкой анимации аватара", [
        (J + "ui/ProfileActivity.java", r"\} else \{(?=\s*\n\s*avatarGooey\.setPullProgress\(pullUpProgress\);)",
         lambda m: "} else if (%s.noGooeyAvatar()) {\n"
                   "            avatarGooey.setAlpha(MathUtils.clamp(1f - (pullUpProgress - 0.5f) / 0.5f, 0f, 1f));\n"
                   "            avatarGooey.setBlurIntensity(0f);\n"
                   "            avatarGooey.setGooeyEnabled(false);\n"
                   "        } else {" % MC),
    ])
    soft_feature("no_media_preview", "без миниатюр в списке чатов", [
        (J + "ui/Cells/DialogCell.java", r"private int thumbsCount;", None),
        (J + "ui/Cells/DialogCell.java", r"public void updateMessageThumbs\(\) \{",
         after("\n        if (%s.noMediaPreview()) { thumbsCount = 0; return; }" % MC)),
    ])
    da = J + "ui/DialogsActivity.java"
    st = "org.telegram.ui.MelgramScrollTop"
    soft_feature("scroll_top_button", "кнопка «Наверх» в списке чатов", [
        (J + "ui/Components/FragmentFloatingButton.java", r"public static FrameLayout\.LayoutParams createSubButtonLayoutParams\(\)", None),
        (da, r"contentView\.addView\(floatingButtonStories, FragmentFloatingButton\.createSubButtonLayoutParams\(\)\);",
         after("\n        %s.create(context, resourceProvider, contentView, DialogsActivity.this);" % st)),
        (da, r"viewPage\.addView\(viewPage\.listView, LayoutHelper\.createFrame\(LayoutHelper\.MATCH_PARENT, LayoutHelper\.MATCH_PARENT\)\);",
         after("\n            %s.watch(DialogsActivity.this, viewPage.listView);" % st)),
        (da, r"final float baseTranslationY = top\s*- floatingButtonPanOffset;",
         after("\n        %s.updateOffset(DialogsActivity.this, baseTranslationY, floatingButtonStories);" % st)),
        (da, r"final boolean isVisible = !\(onlySelect && initialDialogsType != 10[^;]*;",
         after("\n        %s.setAllowed(DialogsActivity.this, isVisible);" % st)),
        (da, r"floatingButtonStories\.updateColors\(\);",
         after("\n                %s.updateColors(DialogsActivity.this);" % st)),
    ])
    soft_feature("account_background", "фоновое соединение по аккаунтам", [
        (J + "tgnet/ConnectionsManager.java", r"public void setPushConnectionEnabled\(boolean value\)", None),
        (J + "tgnet/ConnectionsManager.java", r"public boolean isPushConnectionEnabled\(\) \{",
         after("\n        if (%s.backgroundDisabled(currentAccount)) return false;" % MC)),
    ])
    soft_feature("tablet_mode", "режим планшета", [
        (J + "messenger/AndroidUtilities.java", r"public static boolean isTabletForce\(\) \{",
         after("\n        int melgramTablet = %s.tabletMode();\n"
               "        if (melgramTablet != %s.TABLET_AUTO) return melgramTablet == %s.TABLET_ON;" % (MC, MC, MC))),
    ])


ICON_TITLES = ["Голубая", "Новогодняя", "Чикенбургер", "Мику и ретро-ПК", "Улыбка", "Банан-тюлень", "Ооо бля", "Подсолнухи"]


def patch_icon_set():
    """Built-in launcher icons, switchable in Telegram's own icon picker and in Melgram."""
    count = len(ICON_TITLES)
    enum = "".join('        MELGRAM_%d("MelgramIcon%d", R.mipmap.melgram_icon_%d_bg, R.drawable.melgram_transparent, R.string.AppIconMelgram%d),\n'
                   % (n, n, n, n) for n in range(1, count + 1)).rstrip(",\n") + ";"
    aliases = "".join(
        '        <activity-alias\n'
        '            android:enabled="false"\n'
        '            android:name="org.telegram.messenger.MelgramIcon%d"\n'
        '            android:targetActivity="org.telegram.ui.LaunchActivity"\n'
        '            android:icon="@mipmap/melgram_icon_%d"\n'
        '            android:roundIcon="@mipmap/melgram_icon_%d_round"\n'
        '            android:exported="true">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '                <category android:name="android.intent.category.MULTIWINDOW_LAUNCHER" />\n'
        '            </intent-filter>\n'
        '            <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />\n'
        '        </activity-alias>\n\n' % (n, n, n) for n in range(1, count + 1))
    strings = "".join('    <string name="AppIconMelgram%d">%s</string>\n' % (n, t) for n, t in enumerate(ICON_TITLES, start=1))
    lic = J + "ui/LauncherIconController.java"
    soft_feature("icon_set", "встроенные иконки (%d шт.)" % count, [
        # default entry shows the Melgram picture; Telegram's own logo variants are replaced by ours
        (lic, r'DEFAULT\("DefaultIcon", R\.drawable\.icon_background_sa, R\.mipmap\.icon_foreground_sa, R\.string\.AppIconDefault\),',
         lambda m: 'DEFAULT("DefaultIcon", R.mipmap.melgram_icon_bg, R.drawable.melgram_transparent, R.string.AppIconDefault),'),
        (lic, r'(?s)\n(\s*)VINTAGE\(.*?\);', lambda m: "\n" + enum),
        ("TMessagesProj/src/main/AndroidManifest.xml", r'\n(\s*)<activity-alias\s*\n\s*android:enabled="true"\s*\n\s*android:name="org\.telegram\.messenger\.DefaultIcon"',
         lambda m: "\n" + aliases + m.group(0).lstrip("\n")),
        ("TMessagesProj/src/main/res/values/strings.xml", r"</resources>\s*$", lambda m: strings + "</resources>\n"),
    ])


def main():
    check_inputs()
    print("[melgram] исходники: " + SRC)
    patch_accounts()
    patch_identity()
    patch_abis()
    patch_icon()
    patch_icon_set()
    patch_features()
    patch_updates()
    if DISABLED:
        print("[melgram] пропущены функции: " + ", ".join(DISABLED))
    print("\n[melgram] Готово: %s, пакет %s, аккаунтов: %d" % (NAME, PKG, ACCOUNTS))


if __name__ == "__main__":
    main()
