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
    print("[melgram] %-55s %d файлов" % ("экран выбора иконки", n))

    activity = (
        '        <activity\n'
        '            android:name="org.telegram.messenger.MelgramIconActivity"\n'
        '            android:label="@string/MelgramIconLong"\n'
        '            android:theme="@android:style/Theme.Material.Light.NoActionBar"\n'
        '            android:exported="true" />\n\n'
        '    </application>')
    rx("TMessagesProj/src/main/AndroidManifest.xml", r"</application>", activity, label="регистрация экрана иконки")

    # Long press on the app icon -> "Сменить иконку Melgram"
    shortcut = (
        r'\g<0>' + "\n"
        '    <shortcut\n'
        '        android:shortcutId="melgram_icon"\n'
        '        android:enabled="true"\n'
        '        android:icon="@drawable/melgram_shortcut_icon"\n'
        '        android:shortcutShortLabel="@string/MelgramIconShort"\n'
        '        android:shortcutLongLabel="@string/MelgramIconLong">\n'
        '        <intent\n'
        '            android:action="android.intent.action.VIEW"\n'
        '            android:targetPackage="%s"\n'
        '            android:targetClass="org.telegram.messenger.MelgramIconActivity" />\n'
        '    </shortcut>\n' % PKG)
    rx("TMessagesProj/src/main/res/xml/shortcuts.xml", r"<shortcuts[^>]*>", shortcut, label="ярлык по долгому нажатию")

    # Settings -> "Иконка Melgram", right after Language
    sa = "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"
    rx(sa,
       r"(\n(\s*)items\.add\(SettingCell\.Factory\.of\(10,[^\n]*SettingsLanguage[^\n]*\n)",
       lambda m: m.group(1) + m.group(2) +
       'items.add(SettingCell.Factory.of(1001, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, '
       'R.drawable.melgram_settings_icon, "Иконка Melgram", "Своя картинка из галереи"));\n',
       label="пункт в настройках")
    rx(sa,
       r"(\n(\s*)case 10:\s*\n\s*presentSettingFragment\(new LanguageSelectActivity\(\)\);\s*\n\s*break;)",
       lambda m: m.group(1) + "\n" + m.group(2) + "case 1001:\n" + m.group(2) +
       "    if (getParentActivity() != null) getParentActivity().startActivity(new android.content.Intent(getParentActivity(), org.telegram.messenger.MelgramIconActivity.class));\n" +
       m.group(2) + "    break;",
       label="открытие экрана иконки")


def main():
    check_inputs()
    print("[melgram] исходники: " + SRC)
    patch_accounts()
    patch_identity()
    patch_abis()
    patch_icon()
    print("\n[melgram] Готово: %s, пакет %s, аккаунтов: %d" % (NAME, PKG, ACCOUNTS))


if __name__ == "__main__":
    main()
