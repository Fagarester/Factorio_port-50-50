# Gladio — клиент для этого форка

Gladio — это трансляция OpenGL → OpenGL ES. Две части:
- **клиент** (эта папка) — `libGL.so.1` для glibc-rootfs, игра думает, что это обычный libGL; команды уходят по сокету;
- **сервер** — нативная библиотека `gladiolegacy` внутри приложения (`app/app/src/main/cpp/gladiolegacy`, класс `LegacyGLXExtension`), выполняет их на OpenGL ES устройства.

Клиент собирается отдельно, как `input-interposer` и `fake-udev`: GitHub → Actions → **Build Gladio client** → Run workflow
(ARM-раннер). Артефакт `gladio` содержит `gladio-<версия>.tzst`. Файл положить в
`app/app/src/main/assets/graphics_driver/` и поставить ту же версию в `DefaultVersion.GLADIO`.

Строки `/data/data/com.winlator/files/rootfs` в исходниках оставлены специально: форк подменяет их на свой пакет при распаковке.

Что видно при запуске: вызовы, которых в клиенте нет, пишутся в лог игры строкой `gladio: unimplemented call <имя>`
(диагностика собирает их в `result.txt`). Новый вызов добавляется в двух местах: клиент (`src/gl_calls.c`, отправка в кольцо)
и сервер (`request_handler.c`, обработчик `gd_handle_<имя>`; номера запросов в `request_codes.h` у обеих сторон одинаковые).
