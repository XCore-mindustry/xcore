cmd-map-description = Детально показати одну мапу
cmd-map-param-server-description = Назва сервера
cmd-map-param-file_name-name = мапа
cmd-map-param-file_name-description = Мапа
cmd-maps-review-name = перевірка
cmd-maps-review-description = Почати перевірку карти в цій публікації
maps-file-needed = Прикріпіть карту у форматі .msav до цієї публікації. Файл має завантажити її автор.
maps-review-in-post = Використовуйте /maps review усередині публікації на форумі карт.
maps-review-no-file = Перевірку не розпочато. Перевірте теги публікації; автор має прикріпити файл .msav.
maps-map = Карта
maps-file = Файл
maps-file-info = { $file } · { $size } байт
maps-mode = Режим
maps-dimensions = { $width } × { $height }
maps-validation = Перевірка файлу
maps-checks = Перевірка на сервері
maps-checks-pending = Очікуємо ігровий сервер. Бот повторить перевірку.
maps-accept = Прийняти
maps-recheck = Перевірити знову
maps-rechecked = Результати перевірки карти оновлено.
maps-select-server = Виберіть сервер для встановлення карти.
maps-no-server = Зараз немає доступних ігрових серверів. Спробуйте пізніше.
maps-accept-anyway = Під час перевірки карти знайдено помилки. Усе одно прийняти й установити її?
maps-accept-pending = Перевірку на сервері ще не завершено. Установити карту без її результатів?
maps-not-valid = Файл не пройшов перевірку формату або вкладення. Перевірте його знову, перш ніж приймати.
maps-file-changed = Перевірене вкладення видалено або змінилося. Перевірте актуальний файл перед прийняттям.
maps-review-changed = Карта або результати перевірки змінилися. Натисніть «Прийняти» знову, щоб переглянути актуальні результати.
maps-upload = Результат завантаження
maps-upload-unknown = Результат завантаження невідомий. Повторне завантаження заблоковано. Працівнику потрібно перевірити сервер; бот завершить прийняття, якщо з’явиться запис про успішне встановлення цієї карти.
maps-upload-result = { $file }: { $status }. { $detail }
maps-upload-status-installed = установлена
maps-upload-status-download_failed = не вдалося завантажити
maps-upload-status-invalid_map = некоректна карта
maps-upload-status-metadata_failed = не вдалося зберегти метадані; перевірте встановлений файл перед повторною спробою
maps-installed = Установлена карта
maps-installed-info = { $file } · { $server }
maps-outcome-accepted = Вашу карту { $file } прийнято за заявкою №{ $number } і встановлено на сервері { $server }.
maps-outcome-rejected = Вашу карту відхилено за заявкою №{ $number }. Причина: { $reason }
maps-severity-error = Помилки
maps-severity-warning = Попередження
maps-severity-info = Інформація
maps-more = … і ще { $count }
maps-finding-count = кількість: { $count }
maps-invalid-extension = Прикріпіть файл .msav.
maps-invalid-size = Файл порожній, перевищує допустимий розмір або його розмір змінився.
maps-invalid-inflation = Розпакований файл перевищує граничний розмір.
maps-invalid-truncated = Файл неповний.
maps-invalid-zlib = Файл не містить повного потоку zlib.
maps-invalid-magic = У файлі немає коректного заголовка MSAV.
maps-invalid-version = Ця версія збереження не підтримується. Допустимі версії 1–13.
maps-invalid-meta = Метадані файлу пошкоджені або перевищують допустимий розмір.
maps-invalid-unavailable = Не вдалося прочитати вкладення. Бот повторить спробу.
maps-finding-size-out-of-range = Розміри карти перевищують обмеження перевірки
maps-finding-no-core = Ядро не знайдено
maps-finding-no-spawn = Точку появи ворогів не знайдено
maps-finding-pvp-single-team = Для PvP потрібні ядра щонайменше двох команд
maps-finding-attack-no-enemy-core = Команда противника не має ядра
maps-finding-td-road-floor-mixed = У точках появи використано різні типи підлоги дороги
maps-finding-td-road-floor-unexpected = Підлога дороги не відповідає налаштуванням
maps-finding-td-road-disconnected = Дорога не доходить до ядра
maps-finding-td-spawn-not-at-road-start = Точка появи розташована посеред дороги
maps-finding-td-border-wall-missing = Уздовж дороги бракує стін
maps-finding-td-drop-zone-radius = Радіус зони висадки не дорівнює нулю
maps-finding-td-waves-enabled = Увімкнено стандартні хвилі
maps-finding-invalid-map = Сервер не зміг прочитати карту
maps-finding-inspection-mismatch = Сервер перевірив інший файл; повторіть перевірку перед прийняттям
maps-upload-status-superseded = замінена іншим завантаженням
maps-upload-status-rejected = відхилена сервером
maps-finding-missing-name = Карта не має назви
maps-finding-missing-author = Автора карти не вказано
maps-finding-requires-mods = Для карти потрібні моди
maps-finding-data-patches = Карта змінює геймплей дата-патчами; решта перевірок їх не враховувала
maps-patches-attached = Дата-патчі цієї карти ({ $count }), файл { $sha }:
maps-preview-attached = Рендер цієї мапи, файл { $sha }:
maps-finding-unknown-content = Карта використовує невідомий цьому серверу вміст
maps-badge-link-needed = Автор карти не прив’язав ігровий акаунт, тому значок Map maker ще не видано. Бот видасть його сам, коли акаунт прив’яжуть (протягом 30 днів).

# /maps list

maps-list-timeout = Сервер не відповів (тайм-аут).
maps-list-empty = На сервері `{ $server }` немає карт
maps-list-empty-creator = На сервері `{ $server }` немає карт цього автора.
maps-list-title = Карти на `{ $server }`
maps-list-footer = Сортування: { $sort } • Сторінка { $page }/{ $pages } • усього карт: { $total } • на сторінці: { $shown }
maps-list-open = Відкрити мапу…
maps-list-open-gone = Цієї мапи більше немає у списку.
maps-list-legend = 🎨 заявлений автор • 🔧 виправив(-ла) • ⬆ додано через Discord
maps-list-sort-reputation = за репутацією
maps-list-sort-popularity = за популярністю
maps-list-sort-name = за назвою
maps-list-unknown = Невідомо
maps-list-by = автор `{ $author }`
maps-list-file-author = автор у файлі `{ $author }`
maps-list-reputation = реп. { NUMBER($reputation, useGrouping: 1) }

# /map

maps-card-not-found = На `{ $server }` немає мапи `{ $file }`.
maps-card-author = Автор
maps-card-size = Розмір
maps-card-mode = Режим
maps-card-rating = Оцінки
maps-card-activity = Активність
maps-card-activity-value = популярність { NUMBER($popularity, maximumFractionDigits: 2) } • інтерес { NUMBER($interest, maximumFractionDigits: 2) }
maps-card-discord = Додана через Discord
maps-card-file = Файл
maps-card-review = Розгляд
maps-card-footer = Сервер: { $server }
maps-render-time = Відмальовано за { NUMBER($seconds, maximumFractionDigits: 2) } с
maps-card-render-button = Детальний рендер
maps-card-layers = Шари рендеру…
maps-card-layer-buildings = Будівлі
maps-card-layer-buildings-hint = Блоки та їхні тіні; без них видно лише рельєф
maps-card-layer-darkness = Затемнення стін
maps-card-layer-darkness-hint = Темрява над стінами та краєм мапи, як у грі
maps-card-layer-ores = Руди
maps-card-layer-ores-hint = Приглушує все, крім руд, щоб оцінити ресурси
maps-card-layer-grid = Сітка координат
maps-card-layer-grid-hint = Лінія кожні 10 тайлів, координати кожні 50
maps-card-layer-spawns = Спавни та зони висадки
maps-card-layer-spawns-hint = Де з'являються вороги; юніти всередині кола гинуть
maps-card-render-timeout = Рендерер не відповів вчасно. Спробуйте пізніше.
maps-card-render-busy = Рендерер зайнятий іншими мапами. Спробуйте за хвилину.
maps-card-render-cooldown = Новий рендер можна запустити через { $seconds } с.
maps-card-render-failed = Не вдалося відмалювати цю мапу.
maps-card-render-unavailable = Рендерер зараз не запущений. Спробуйте пізніше.

# Comparing a file with the maps already installed

maps-match-same-bytes = той самий файл
maps-match-same-file = та сама назва файлу
maps-match-same-title = та сама назва карти й автор
maps-candidate = { $file } — { $name }, автор { $author } ({ $reasons })
maps-choice-expired = Вибір не зроблено вчасно; нічого не змінено.
maps-target-choose = Схожа карта вже встановлена на `{ $server }`. Що зробити з цим файлом?
maps-target-replace = Замінити { $file }
maps-target-new = Встановити як нову карту { $file }
maps-target-overwrite = Перезаписати { $file } (інша карта)
maps-target-unlisted = Не вдалося отримати список карт `{ $server }`, дублікати не перевірено. Все одно встановити карту як { $file }?

# /maps upload

cmd-maps-upload-param-replace-description = Встановлена карта, яку замінює файл (лише для одного файлу)
maps-upload-checking = Звіряю файли карт ({ $count }) з `{ $server }`…
maps-upload-uploading = Завантажую файли карт ({ $count }) на `{ $server }`…
maps-upload-invalid-no-files = Не прикріплено жодного файлу .msav.
maps-upload-invalid-same-names = Усі вкладення мають мати різні назви файлів.
maps-upload-invalid-invalid = Дані завантаження некоректні; на сервер нічого не надіслано.
maps-upload-replace-one = `replace` працює лише з одним файлом .msav.
maps-upload-replace-missing = На `{ $server }` немає карти { $file }, замінювати нічого. Нічого не завантажено.
maps-upload-confirm-title = Перевірте перед завантаженням на `{ $server }`:
maps-plan-new = { $file } — нова карта
maps-plan-same = { $file } — точно такий самий файл уже встановлено
maps-plan-update = { $file } — оновить встановлену версію
maps-plan-replace = { $source } → замінить { $file }
maps-plan-unchecked = { $file } — не звірено зі встановленими картами
maps-plan-overwrite = { $file } — ⚠️ перезапише іншу карту:
maps-plan-duplicate = { $file } — ⚠️ схоже на вже встановлену карту:
maps-upload-button-replace = Замінити { $file }
maps-upload-button-as-is = Завантажити як є
maps-upload-button-cancel = Скасувати
maps-upload-cancelled = Завантаження скасовано; на сервер нічого не надіслано.
maps-upload-result-title = Карти на `{ $server }`:
maps-upload-row = { $status } — { $file }
maps-upload-label-installed = ✅ Встановлено, атрибуцію збережено
maps-upload-label-download_failed = ❌ Не вдалося завантажити або записати файл
maps-upload-label-invalid_map = ❌ Некоректний файл карти
maps-upload-label-superseded = ⚠️ Замінено новішим завантаженням
maps-upload-label-metadata_failed = ⚠️ Встановлено, але атрибуцію не збережено
maps-upload-label-rejected = ❌ Неприпустима назва файлу
maps-upload-note-new = нова карта
maps-upload-note-update = оновлено встановлену версію
maps-upload-note-same = цей самий файл уже було встановлено
maps-upload-note-replaced = з { $source }
maps-upload-unchecked = Не вдалося отримати список карт `{ $server }`: дублікати не перевірено.
maps-upload-timeout = Сервер ще не підтвердив результат. Частину файлів уже може бути встановлено; перевірте /maps list перед повтором.
maps-upload-unconfirmed = Не вдалося підтвердити результат завантаження. Перевірте /maps list перед повтором.

# /maps remove

maps-remove-empty = Назва файлу карти не може бути порожньою.
maps-remove-question = Видалити { $file } з `{ $server }`?
maps-remove-unlisted = ⚠️ Не вдалося отримати список карт сервера, тому карту показати не можна.
maps-remove-siblings = Ця сама карта є й в інших файлах:
maps-remove-backup = Копію файлу буде опубліковано в цьому каналі з кнопкою **Повернути**.
maps-remove-no-backup = ⚠️ Сервер не надіслав копію файлу: після видалення карту не повернути.
maps-remove-button-remove = Видалити
maps-remove-button-cancel = Скасувати
maps-remove-cancelled = Видалення карти скасовано.
maps-remove-removing = Видаляю { $file }…
maps-remove-duplicate = Це видалення карти вже оброблено.
maps-remove-timeout = Сервер не відповів (тайм-аут).
maps-remove-done = 🗑️ `{ $server }`: { $file } видалено. { $detail }
maps-remove-failed = ❌ `{ $server }`: { $file } не видалено. { $detail }
maps-remove-public-pending = 🗑️ { $user } видаляє { $file } з `{ $server }`…
maps-remove-public-done = 🗑️ { $user } видалив(-ла) { $file } з `{ $server }`. Копію файлу додано.
maps-remove-public-failed = { $user } намагався(-лася) видалити { $file } з `{ $server }`; нічого не видалено.
maps-remove-public-no-backup = Копію файлу не збережено.

# Restore

maps-restore-button = Повернути
maps-restore-denied = Повертати карти можуть лише перевіряльники карт.
maps-restore-gone = Копія цієї карти більше недоступна.
maps-restore-done-already = Цю карту вже повернуто.
maps-restore-busy = Цю карту повертають просто зараз.
maps-restore-no-server = `{ $server }` не відповів; нічого не повернуто.
maps-restore-present = На `{ $server }` уже є файл { $file }. Спочатку видаліть його або завантажте цю копію через /maps upload.
maps-restore-present-same = На `{ $server }` уже є саме цей файл: { $file }.
maps-restore-result = { $file } на `{ $server }`: { $status }

# /maps duplicates

cmd-maps-duplicates-description = Знайти карти, встановлені двічі (перевіряльник карт)
maps-duplicates-title = Можливі дублікати на { $server }
maps-duplicates-none = На `{ $server }` дублікатів карт не знайдено.
maps-duplicates-same-bytes = **Той самий файл під різними назвами:**
maps-duplicates-same-title = **Однакові назва карти й автор:**

# Maps log

maps-log-upload = ⬆ { $user } використав(-ла) /maps upload.
maps-log-accepted = ✅ { $user } прийняв(-ла) заявку №{ $number }: { $file } на `{ $server }`
maps-log-removed = 🗑️ { $user } видалив(-ла) { $file } з `{ $server }`.
maps-log-restored = ♻️ { $user } повернув(-ла) { $file } на `{ $server }`.

# Revisions

maps-update = Оновити
maps-revision = Ревізія
maps-revision-info = Ревізія { $revision } · попередня: заявка №{ $case }, { $status }
maps-revision-status-resolved = прийнята
maps-revision-status-rejected = відхилена
maps-revision-status-cancelled = скасована
maps-installed-now = Зараз встановлена
maps-changes = Що змінилося з попередньої ревізії
maps-changes-identical = Файл той самий, що в ревізії { $revision }.
maps-changes-size = Розмір файлу: { $before } → { $after } байт
maps-changes-dimensions = Розмір карти: { $before } → { $after }
maps-changes-cores = Ядра: { $before } → { $after }
maps-changes-patches = Дата-патчі: { $before } → { $after }
maps-changes-none = Розмір, ядра й дата-патчі не змінилися.
maps-select-server-update = Оберіть сервер. Першим іде той, де стоїть попередня версія; оновлення замінить її там.
maps-server-installed = { $server } — оновити { $file }
maps-accept-plan = Карту буде встановлено як { $file } на `{ $server }`.
maps-review-nothing-new = Від останньої перевірки автор не викладав у цей пост нових файлів.
maps-review-binding-both = Вкажіть і `server`, і `replaces` — або нічого з цього.
maps-outcome-updated = Оновлення вашої карти { $file } прийнято за заявкою №{ $number } і встановлено на сервері { $server }.
maps-outcome-fixed = Ваше виправлення карти { $file } прийнято за заявкою №{ $number } і встановлено на сервері { $server }. Творець карти лишається тим самим, вас записано як того, хто її виправив.

# Request changes

maps-changes-button = На доопрацювання
maps-changes-reason = Що автору треба змінити?
maps-changes-requested = Автору надіслано запит на доопрацювання. Заявка лишається відкритою; наступний файл автора буде перевірено знову.
maps-changes-requested-field = Запитано доопрацювання
maps-changes-requested-note = { $user }, перевіряльник просить доопрацювати карту перед прийняттям: { $reason }
    Викладіть виправлений файл .msav у цей пост.

# A fix of someone else's map

maps-fixed-by = Виправив(-ла)
maps-card-fixed-by = Виправив(-ла)
maps-creator-choose = У { $file } не записано творця. Хто зробив цю карту?
maps-creator-author = Автор цього поста
maps-creator-fix = Хтось інший; цей пост її виправляє

# /maps upload of a file under review, /maps rollback

maps-upload-under-review = Цей файл на перевірці в заявці №{ $number } у цьому пості. Скористайтеся кнопками на її картці, щоб заявку закрито, а автор отримав атрибуцію.
cmd-maps-rollback-description = Повернути попередню прийняту ревізію карти цього поста (перевіряльник карт)
maps-rollback-nothing = У цього поста немає попередньої прийнятої ревізії.
maps-rollback-choose = Зараз встановлена: { $file } на `{ $server }`, із заявки №{ $number }. Якою прийнятою ревізією її замінити?
maps-rollback-option = Ревізія { $revision } · заявка №{ $number }
maps-rollback-running = Повертаю попередню ревізію як { $file }…
maps-rollback-file-gone = Файлу заявки №{ $number } більше немає в цьому пості, або він змінився. Нічого не завантажено.
maps-rollback-note = ↩️ { $user } повернув(-ла) ревізію { $revision } (заявка №{ $number }) замість заявки №{ $current }: { $file } на `{ $server }`.

# Protocol 0.19.0: conditional writes and files kept aside

maps-upload-label-conflict = ⚠️ Не записано: на сервері вже інший файл
maps-upload-status-conflict = не записано: на сервері вже інший файл. Натисніть «Прийняти» знову, щоб побачити, що там
maps-upload-note-conflict = перевірте /maps list і повторіть
maps-upload-note-kept = попередній файл збережено на сервері (/maps trash)
maps-remove-conflict = ⚠️ `{ $server }`: { $file } змінилася відтоді, як ви її дивилися; нічого не видалено.
maps-remove-public-server-copy = Сервер зберіг копію файлу.
maps-restore-status-restored = ♻️ повернуто разом з атрибуцією
maps-restore-status-not_found = у сервера більше немає цієї копії
maps-restore-status-conflict = назва зайнята; нічого не змінено
maps-restore-status-failed = сервер не зміг її повернути
maps-list-revision = рев. { $revision }
cmd-maps-trash-description = Карти, які сервер відклав під час видалення чи заміни (перевіряльник карт)
maps-trash-title = Файли, які `{ $server }` відклав замість видалення. Оберіть, який повернути:
maps-trash-empty = У `{ $server }` немає відкладених файлів.
maps-trash-row = { $file } — { $name } · { $reason } · { $date }
maps-trash-reason-removed = видалено
maps-trash-reason-replaced = замінено завантаженням
maps-trash-restoring = Повертаю { $file }…
