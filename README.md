# Melgram

Личный форк официального Telegram для Android (исходники: github.com/DrKLO/Telegram, версия 13.0.3).

Что изменено:
- до **6 аккаунтов** без Premium (число можно поменять при запуске сборки);
- название **Melgram** и свой пакет `com.melgram.app` — ставится рядом с обычным Telegram;
- своя иконка вместо логотипа Telegram;
- **Настройки → Иконка Melgram** (или долгое нажатие на значок → «Иконка»): выбрать картинку из галереи, приложение само обрежет её под формат иконки и поставит на рабочий стол.

## Как собрать (можно с телефона)

1. **Ключи.** Зайди на my.telegram.org → войди по номеру → *API development tools* → создай приложение (Title: Melgram, Short name: melgram, Platform: Android). Скопируй `api_id` и `api_hash`.
2. **Репозиторий.** На github.com нажми **+ → New repository**, имя `melgram`, тип **Public** (у публичных сборки бесплатные и на более мощных машинах; ключи всё равно хранятся в секретах и никому не видны).
3. **Файлы.** *Add file → Upload files* → загрузи `melgram.zip`.
   Если хочешь другую стандартную иконку, загрузи рядом картинку с именем `icon.jpg` или `icon.png`.
4. **Сценарий сборки.** *Add file → Create new file*, в имени файла напиши `.github/workflows/build.yml`, вставь содержимое `build.yml` и нажми *Commit changes*.
5. **Секреты.** *Settings → Secrets and variables → Actions → New repository secret*:
   - `API_ID` — число с my.telegram.org;
   - `API_HASH` — строка с my.telegram.org.
6. **Сборка.** Вкладка *Actions* → *Собрать Melgram* → *Run workflow*. Сборка идёт 1–2 часа.
7. **Скачать.** Готовый APK появится на главной странице репозитория справа в **Releases**.

## Если уведомления приходят с задержкой

Пуш-уведомления Google привязаны к официальному Telegram, поэтому в форке открой *Настройки → Уведомления*, пролистай в самый низ и включи оба пункта про фоновую работу (в английском интерфейсе — *Keep-Alive Service* и *Background Connection*).

## Обновление на новую версию Telegram

В `build.yml` поменяй `TELEGRAM_COMMIT` на новый коммит из DrKLO/Telegram и запусти сборку. Если Telegram сильно поменял код, сборка остановится с понятной ошибкой `[melgram] ОШИБКА: ...`.
