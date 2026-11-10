cmd-map-description = Подробно показать одну карту
cmd-map-param-server-description = Название сервера
cmd-map-param-file_name-name = карта
cmd-map-param-file_name-description = Карта
cmd-maps-review-name = проверка
cmd-maps-review-description = Начать проверку карты в этой публикации
maps-file-needed = Прикрепите карту в формате .msav к этой публикации. Файл должен загрузить её автор.
maps-review-in-post = Используйте /maps review внутри публикации на форуме карт.
maps-review-no-file = Проверка не начата. Проверьте теги публикации; автор должен прикрепить файл .msav.
maps-map = Карта
maps-file = Файл
maps-file-info = { $file } · { $size } байт
maps-mode = Режим
maps-dimensions = { $width } × { $height }
maps-validation = Проверка файла
maps-checks = Проверка на сервере
maps-checks-pending = Ожидаем игровой сервер. Бот повторит проверку.
maps-accept = Принять
maps-recheck = Проверить снова
maps-rechecked = Результаты проверки карты обновлены.
maps-select-server = Выберите сервер для установки карты.
maps-no-server = Сейчас нет доступных игровых серверов. Попробуйте позже.
maps-accept-anyway = При проверке карты найдены ошибки. Всё равно принять и установить её?
maps-accept-pending = Проверка на сервере ещё не завершена. Установить карту без её результатов?
maps-not-valid = Файл не прошёл проверку формата или вложения. Проверьте его снова, прежде чем принимать.
maps-file-changed = Проверенное вложение удалено или изменилось. Проверьте актуальный файл перед принятием.
maps-review-changed = Карта или результаты проверки изменились. Нажмите «Принять» снова, чтобы просмотреть актуальные результаты.
maps-upload = Результат загрузки
maps-upload-unknown = Результат загрузки неизвестен. Повторная загрузка заблокирована. Сотруднику нужно проверить сервер; бот завершит принятие, если появится запись об успешной установке этой карты.
maps-upload-result = { $file }: { $status }. { $detail }
maps-upload-status-installed = установлена
maps-upload-status-download_failed = не удалось скачать
maps-upload-status-invalid_map = некорректная карта
maps-upload-status-metadata_failed = не удалось сохранить метаданные; проверьте установленный файл перед повторной попыткой
maps-installed = Установленная карта
maps-installed-info = { $file } · { $server }
maps-badge = Создатель карт
maps-badge-pending = Выдача значка «Создатель карт» ещё не завершена. Бот повторяет попытки в течение 30 дней после принятия карты.
maps-outcome-accepted = Ваша карта { $file } принята по заявке №{ $number } и установлена на сервере { $server }.
maps-outcome-rejected = Ваша карта отклонена по заявке №{ $number }. Причина: { $reason }
maps-severity-error = Ошибки
maps-severity-warning = Предупреждения
maps-severity-info = Информация
maps-more = … и ещё { $count }
maps-finding-count = количество: { $count }
maps-invalid-extension = Прикрепите файл .msav.
maps-invalid-size = Файл пуст, превышает допустимый размер или его размер изменился.
maps-invalid-inflation = Распакованный файл превышает предельный размер.
maps-invalid-truncated = Файл неполный.
maps-invalid-zlib = Файл не содержит полного потока zlib.
maps-invalid-magic = В файле отсутствует корректный заголовок MSAV.
maps-invalid-version = Эта версия сохранения не поддерживается. Допустимы версии 1–13.
maps-invalid-meta = Метаданные файла повреждены или превышают допустимый размер.
maps-invalid-unavailable = Не удалось прочитать вложение. Бот повторит попытку.
maps-finding-size-out-of-range = Размеры карты превышают ограничения проверки
maps-finding-no-core = Ядро не найдено
maps-finding-no-spawn = Точка появления врагов не найдена
maps-finding-pvp-single-team = Для PvP нужны ядра как минимум двух команд
maps-finding-attack-no-enemy-core = У команды противника нет ядра
maps-finding-td-road-floor-mixed = В точках появления используются разные типы пола дороги
maps-finding-td-road-floor-unexpected = Пол дороги не соответствует настройкам
maps-finding-td-road-disconnected = Дорога не доходит до ядра
maps-finding-td-spawn-not-at-road-start = Точка появления находится посреди дороги
maps-finding-td-border-wall-missing = Вдоль дороги не хватает стен
maps-finding-td-drop-zone-radius = Радиус зоны высадки не равен нулю
maps-finding-td-waves-enabled = Включены стандартные волны
maps-finding-invalid-map = Сервер не смог прочитать карту
maps-finding-inspection-mismatch = Сервер проверил другой файл; повторите проверку перед принятием
maps-upload-status-superseded = заменена другой загрузкой
maps-upload-status-rejected = отклонена сервером
maps-finding-missing-name = У карты нет названия
maps-finding-missing-author = Автор карты не указан
maps-finding-requires-mods = Для карты нужны моды
maps-finding-data-patches = Карта меняет геймплей дата-патчами; остальные проверки их не учитывали
maps-patches-attached = Дата-патчи этой карты ({ $count }), файл { $sha }:
maps-preview-attached = Рендер этой карты, файл { $sha }:
maps-finding-unknown-content = Карта использует неизвестный этому серверу контент
maps-badge-link-needed = У автора карты не привязан игровой аккаунт, поэтому значок Map maker пока не выдан. Бот выдаст его сам, когда аккаунт привяжут (в течение 30 дней).

# /maps list

maps-list-timeout = Сервер не ответил (тайм-аут).
maps-list-empty = На сервере `{ $server }` нет карт
maps-list-empty-creator = На сервере `{ $server }` нет карт этого автора.
maps-list-title = Карты на `{ $server }`
maps-list-footer = Сортировка: { $sort } • Страница { $page }/{ $pages } • всего карт: { $total } • на странице: { $shown }
maps-list-open = Открыть карту…
maps-list-open-gone = Этой карты больше нет в списке.
maps-list-legend = 🎨 заявленный автор • ⬆ добавлена через Discord
maps-list-sort-reputation = по репутации
maps-list-sort-popularity = по популярности
maps-list-sort-name = по названию
maps-list-unknown = Неизвестно
maps-list-by = автор `{ $author }`
maps-list-file-author = автор в файле `{ $author }`
maps-list-reputation = реп. { NUMBER($reputation, useGrouping: 1) }

# /map

maps-card-not-found = На `{ $server }` нет карты `{ $file }`.
maps-card-author = Автор
maps-card-size = Размер
maps-card-mode = Режим
maps-card-rating = Оценки
maps-card-activity = Активность
maps-card-activity-value = популярность { NUMBER($popularity, maximumFractionDigits: 2) } • интерес { NUMBER($interest, maximumFractionDigits: 2) }
maps-card-discord = Добавлена через Discord
maps-card-file = Файл
maps-card-review = Ревью
maps-card-footer = Сервер: { $server }
maps-render-time = Отрисовано за { NUMBER($seconds, maximumFractionDigits: 2) } с
maps-card-render-button = Детальный рендер
maps-card-layers = Слои рендера…
maps-card-layer-buildings = Постройки
maps-card-layer-buildings-hint = Блоки и их тени; без них виден только рельеф
maps-card-layer-darkness = Затемнение стен
maps-card-layer-darkness-hint = Темнота над стенами и краем карты, как в игре
maps-card-layer-ores = Руды
maps-card-layer-ores-hint = Приглушает всё, кроме руд, чтобы оценить ресурсы
maps-card-layer-grid = Сетка координат
maps-card-layer-grid-hint = Линия каждые 10 тайлов, координаты каждые 50
maps-card-layer-spawns = Спавны и зоны высадки
maps-card-layer-spawns-hint = Где появляются враги; юниты внутри круга погибают
maps-card-render-timeout = Рендерер не ответил вовремя. Попробуйте позже.
maps-card-render-busy = Рендерер занят другими картами. Попробуйте через минуту.
maps-card-render-cooldown = Новый рендер можно запустить через { $seconds } с.
maps-card-render-failed = Не удалось отрисовать эту карту.
maps-card-render-unavailable = Рендерер сейчас не запущен. Попробуйте позже.

# Comparing a file with the maps already installed

maps-match-same-bytes = тот же файл
maps-match-same-file = то же имя файла
maps-match-same-title = то же название и автор
maps-candidate = { $file } — { $name }, автор { $author } ({ $reasons })
maps-choice-expired = Выбор не сделан вовремя; ничего не изменено.
maps-target-choose = Похожая карта уже установлена на `{ $server }`. Что сделать с этим файлом?
maps-target-replace = Заменить { $file }
maps-target-new = Установить как новую карту { $file }
maps-target-overwrite = Перезаписать { $file } (другая карта)
maps-target-unlisted = Не удалось получить список карт `{ $server }`, дубликаты не проверены. Всё равно установить карту как { $file }?

# /maps upload

cmd-maps-upload-param-replace-description = Установленная карта, которую заменяет файл (только для одного файла)
maps-upload-checking = Сверяю файлы карт ({ $count }) с `{ $server }`…
maps-upload-uploading = Загружаю файлы карт ({ $count }) на `{ $server }`…
maps-upload-invalid-no-files = Не прикреплено ни одного файла .msav.
maps-upload-invalid-same-names = У всех вложений должны быть разные имена файлов.
maps-upload-invalid-invalid = Данные загрузки некорректны; на сервер ничего не отправлено.
maps-upload-replace-one = `replace` работает только с одним файлом .msav.
maps-upload-replace-missing = На `{ $server }` нет карты { $file }, заменять нечего. Ничего не загружено.
maps-upload-confirm-title = Проверьте перед загрузкой на `{ $server }`:
maps-plan-new = { $file } — новая карта
maps-plan-same = { $file } — точно такой же файл уже установлен
maps-plan-update = { $file } — обновит установленную версию
maps-plan-replace = { $source } → заменит { $file }
maps-plan-unchecked = { $file } — не сверен с установленными картами
maps-plan-overwrite = { $file } — ⚠️ перезапишет другую карту:
maps-plan-duplicate = { $file } — ⚠️ похоже на уже установленную карту:
maps-upload-button-replace = Заменить { $file }
maps-upload-button-as-is = Загрузить как есть
maps-upload-button-cancel = Отмена
maps-upload-cancelled = Загрузка отменена; на сервер ничего не отправлено.
maps-upload-result-title = Карты на `{ $server }`:
maps-upload-row = { $status } — { $file }
maps-upload-label-installed = ✅ Установлена, атрибуция сохранена
maps-upload-label-download_failed = ❌ Не удалось скачать или записать файл
maps-upload-label-invalid_map = ❌ Некорректный файл карты
maps-upload-label-superseded = ⚠️ Заменена более новой загрузкой
maps-upload-label-metadata_failed = ⚠️ Установлена, но атрибуция не сохранена
maps-upload-label-rejected = ❌ Недопустимое имя файла
maps-upload-note-new = новая карта
maps-upload-note-update = обновлена установленная версия
maps-upload-note-same = этот же файл уже был установлен
maps-upload-note-replaced = из { $source }
maps-upload-unchecked = Не удалось получить список карт `{ $server }`: дубликаты не проверены.
maps-upload-timeout = Сервер ещё не подтвердил результат. Часть файлов уже может быть установлена; проверьте /maps list перед повтором.
maps-upload-unconfirmed = Не удалось подтвердить результат загрузки. Проверьте /maps list перед повтором.

# /maps remove

maps-remove-empty = Имя файла карты не может быть пустым.
maps-remove-question = Удалить { $file } с `{ $server }`?
maps-remove-unlisted = ⚠️ Не удалось получить список карт сервера, поэтому карту показать нельзя.
maps-remove-siblings = Эта же карта есть и в других файлах:
maps-remove-backup = Копия файла будет опубликована в этом канале с кнопкой **Вернуть**.
maps-remove-no-backup = ⚠️ Сервер не прислал копию файла: после удаления карту не вернуть.
maps-remove-button-remove = Удалить
maps-remove-button-cancel = Отмена
maps-remove-cancelled = Удаление карты отменено.
maps-remove-removing = Удаляю { $file }…
maps-remove-duplicate = Это удаление карты уже обработано.
maps-remove-timeout = Сервер не ответил (тайм-аут).
maps-remove-done = 🗑️ `{ $server }`: { $file } удалена. { $detail }
maps-remove-failed = ❌ `{ $server }`: { $file } не удалена. { $detail }
maps-remove-public-pending = 🗑️ { $user } удаляет { $file } с `{ $server }`…
maps-remove-public-done = 🗑️ { $user } удалил(а) { $file } с `{ $server }`. Копия файла приложена.
maps-remove-public-failed = { $user } пытался(-ась) удалить { $file } с `{ $server }`; ничего не удалено.
maps-remove-public-no-backup = Копия файла не сохранена.

# Restore

maps-restore-button = Вернуть
maps-restore-denied = Возвращать карты могут только проверяющие карт.
maps-restore-gone = Копия этой карты больше недоступна.
maps-restore-done-already = Эта карта уже возвращена.
maps-restore-busy = Эту карту возвращают прямо сейчас.
maps-restore-no-server = `{ $server }` не ответил; ничего не возвращено.
maps-restore-present = На `{ $server }` уже есть файл { $file }. Сначала удалите его или загрузите эту копию через /maps upload.
maps-restore-present-same = На `{ $server }` уже есть точно этот файл: { $file }.
maps-restore-result = { $file } на `{ $server }`: { $status }

# /maps duplicates

cmd-maps-duplicates-description = Найти карты, установленные дважды (проверяющий карт)
maps-duplicates-title = Возможные дубликаты на { $server }
maps-duplicates-none = На `{ $server }` дубликатов карт не найдено.
maps-duplicates-same-bytes = **Один и тот же файл под разными именами:**
maps-duplicates-same-title = **Одинаковые название и автор:**

# Maps log

maps-log-upload = ⬆ { $user } использовал(а) /maps upload.
maps-log-accepted = ✅ { $user } принял(а) заявку №{ $number }: { $file } на `{ $server }`
maps-log-removed = 🗑️ { $user } удалил(а) { $file } с `{ $server }`.
maps-log-restored = ♻️ { $user } вернул(а) { $file } на `{ $server }`.
