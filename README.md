# MiniBrowser

Мінімалістичний браузер для Android: Kotlin + Jetpack Compose + системний Android WebView.
Темна тема за замовчуванням, акцент #8875FF, edge-to-edge з коректними WindowInsets.

> **Чесний статус збірки.** Код написано та перевірено синтаксичним розбором компілятором Kotlin 2.0.21,
> але **повна компіляція та збірка APK у середовищі розробки не виконувалась** — там немає Android SDK
> і закритий доступ до Google/Maven. Перша збірка в Android Studio може показати дрібні помилки
> компіляції (імена API Compose/androidx.webkit залежать від версій) — їх треба виправити за підказками IDE.
> Нічого з описаного нижче не слід вважати перевіреним на пристрої, доки ви самі не запустите застосунок.

## Збірка APK

Потрібно: Android Studio Ladybug (2024.2) або новіша, JDK 17, Android SDK 35.

1. `File → Open` → папка `MiniBrowser`. Дочекайтесь Gradle Sync (завантажить Gradle 8.9 і залежності).
2. Debug-APK: `Build → Build Bundle(s) / APK(s) → Build APK(s)`
   або в терміналі: `./gradlew assembleDebug`
   → `app/build/outputs/apk/debug/app-debug.apk` (пакет `com.minibrowser.debug`).
3. Release: `./gradlew assembleRelease` дає непідписаний APK; підпишіть власним ключем
   (`Build → Generate Signed Bundle / APK`). Мінімізація вимкнена навмисно.
4. Встановлення: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Версії: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, androidx.webkit 1.12.1, minSdk 26, target/compile 35.

## Структура

```
app/src/main/java/com/minibrowser/
  MainActivity.kt          edge-to-edge, кореневий Compose, єдиний «Назад», fullscreen, FLAG_SECURE
  BrowserViewModel.kt      стан UI, менеджери, реалізація EngineHost, діалоги
  web/   BrowserEngine, BrowserTab, TabManager, EngineHost, InternalPages
  data/  HistoryManager, BookmarkManager, DownloadsManager, BrowserSettings, UrlUtils, JsonFileStore, Models
  privacy/ PrivacyEngine, TrackingBlocker, FilterListManager, FilterRules, CookieManager (SiteCookieManager),
           FingerprintProtection, PrivacyDashboard, SitePermissionsManager, PrivateSessionManager, PrivacyManager
  ui/    BrowserScreen, HomeScreen, TabSwitcherScreen, HistoryScreen, BookmarksScreen, DownloadsScreen,
         SettingsScreen, PrivacyScreens, PrivacyDashboardSheet, Dialogs, Components, Theme
app/src/main/assets/filters/  easylist.txt, easyprivacy.txt, malware.txt, fingerprinting.txt, NOTICE.txt
```

## Що реалізовано

- **Вкладки**: створення, закриття, перемикання, сітка з мініатюрами, збереження між запусками, «закрити всі». Приватні вкладки не зберігаються.
- **Адресний рядок**: URL або пошук (Google, DuckDuckGo, Bing, Brave, Startpage, Ecosia), назад/вперед/оновити/стоп, лічильник вкладок, повний екран.
- **Історія** (пошук, видалення, очищення), **закладки** (додати, перейменувати, видалити), **завантаження** (системний DownloadManager, лише http/https).
- **Безпека WebView**: JavaScript і DOM storage увімкнені, доступ до файлів вимкнено, `JavascriptInterface` не використовується,
  помилки SSL **ніколи** не обходяться автоматично (лише явне рішення користувача для хоста), зовнішні посилання — тільки після дотику,
  `intent:` очищується, власні сторінки помилок.
- **Приватність** (розділ 12): блокування реклами/трекерів/шкідливих доменів за EasyList, EasyPrivacy, URLhaus (локальні списки + ручне оновлення),
  панель приватності вкладки (реальні лічильники), менеджер cookie, приватний режим, дозволи сайтів, детектор fingerprinting,
  вимкнення захисту для окремого сайту, автоочищення даних зі списком винятків.

## Обмеження Android WebView (важливо)

- `shouldInterceptRequest` не бачить тіл POST, WebSocket та частини запитів Service Worker — вони не блокуються.
- WebView не повідомляє тип ресурсу; він визначається евристично (розширення, `Accept`, `Sec-Fetch-Dest`). Правила з обмеженням типу можуть спрацьовувати неточно.
- Не підтримуються косметичні правила EasyList (приховування елементів), `$csp`, `$redirect`, `$removeparam`, регулярні вирази. Реклама з того ж домену, що й сайт, може лишатися.
- Сторонні cookie WebView дозволяє лише ввімкнути/вимкнути; кількість відхилених cookie невідома. Дашборд показує число сторонніх запитів, надісланих при вимкнених сторонніх cookie, і так це й підписано.
- Менеджер cookie бачить лише `name=value` для адреси сторінки (без домену, строку, Secure/HttpOnly).
- **Приватний режим**: повна ізоляція cookie/сховища потребує WebView з підтримкою профілів (`MULTI_PROFILE`). Інакше приватні вкладки не пишуть історію й не користуються кешем, але **ділять cookie зі звичайними** — застосунок прямо про це попереджає. Дані мережі (DNS/SNI), провайдер і сайти приватністю режиму не приховуються.
- Детектор fingerprinting потребує `DOCUMENT_START_SCRIPT`; він лише **повідомляє** про звернення до canvas/WebGL/аудіо/пристроїв/батареї й нічого не підміняє.
- Веб-сповіщення Android WebView не підтримує. Завантаження `blob:`/`data:` через DownloadManager неможливе. HTTP-авторизація не реалізована.
- Google Safe Browsing (за замовчуванням увімкнений) надсилає хеш-префікси адрес до Google; його можна вимкнути в налаштуваннях приватності.

## Свідомі відмови

Браузер **не підміняє** User-Agent, часовий пояс, мову, браузерні API, видимість вкладки, події focus/blur/visibilitychange та інші сигнали,
не обходить системи контролю чи моніторингу навчальних платформ. Режиму «версія для ПК» немає саме тому, що він змінює User-Agent.
Телеметрії немає: застосунок не надсилає даних нікуди, крім самих запитів сторінок, ручного оновлення списків фільтрів та (опційно) Safe Browsing.

## Ліцензії списків

EasyList / EasyPrivacy — CC BY-SA 3.0 / GPLv3+, URLhaus (abuse.ch) — CC0. Див. `assets/filters/NOTICE.txt`.
