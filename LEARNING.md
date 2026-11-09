# LEARNING — Factory (подтверждённые уроки проекта)

Краткие проверенные выводы. Подробности — в коде и в комментариях к нему.

## Gladio: текстуры BC
- 2026-11-06. Gladio распаковывал все текстуры BC (DXT/RGTC) в BGRA8. При атласах 8192 мегабазы это 20,4 ГБ против 3,65 ГБ в BC (подсчёт по журналу `GLADIO_TEX_LOG`).
- Adreno 830 (GLES) принимает S3TC и RGTC сам. С v51 сервер передаёт их как есть, если в `GL_EXTENSIONS` есть `GL_EXT_texture_compression_s3tc` / `_rgtc` (`isNativeCompressedFormat`, `GLADIO_BC=0` — выключить).
- Подтверждение: стенд proot, мегабаза 2m_final, пресет maximum, 4096. Картинка правильная, «Sprites loaded» 87 с вместо 422 с.
- Атласы 8192 (`GLADIO_MAX_TEXTURE_SIZE=8192`) по-прежнему дают чёрную сушу даже с BC; вода рисуется. Причина не найдена. Это не нехватка памяти и не ошибка GL (`GLADIO_SERVER_ERRORS=1` чисто).

## Gladio: где время на мегабазе
- 2026-11-06. Обработчики сервера тонкие. Время уходит на вызовы драйвера GLES в игре: glDrawElements ~1,8 мкс, glBindTexture ~0,6, glBindSampler ~0,5, glActiveTexture ~0,43 (`GLADIO_SERVER_PROFILE=1`). Тест в цикле с двумя текстурами (8–30 нс) это не отражает.
- Синхронный режим (обработчик в потоке игры) на мегабазе медленнее потоков: ~27 против ~40 FPS (логи пользователя v48/v49). По умолчанию с v50 — потоки.

## Gladio: «раньше было быстрее» (резервная копия №9, v34c)
- 2026-11-06. Среда выполнения в APK №9 и v51 одинаковая; отличаются только libGL.so.1 и libgladioserver.so. На стенде (мегабаза, 4096, один сервер) клиент №9: кадр 48–53 мс, текущий: 37–39 мс — код не хуже.
- Отличие — настройки: в v34c пресетов не было (спрайты medium для Gladio, эффекты не трогались). Пресет `maximum` (v40) пишет спрайты high и все эффекты в config.ini при каждом запуске. На стенде: спрайты medium + все эффекты — кадр 31–34 мс и 0,95 ГБ текстур против 37–39 мс и 2,5 ГБ при high.
- Пресет `custom` не откатывает значения, которые уже записал другой пресет.
- Сравнивая старый клиент с новым сервером, ограничивать ответ сервера `GLADIO_SERVER_MAX_TEXTURE_SIZE=4096`: старый клиент передаёт игре значение сервера как есть, а сервер с v44 отвечает реальным пределом (16384).

## ANGLE через libhybris (режим «напрямую» + ANGLE, v53)
- 2026-11-06. libhybris загружает системный ANGLE переменными `LIBEGL=libEGL_angle.so LIBGLESV2=libGLESv2_angle.so`. Без правки загрузчика (q.so) было два сбоя:
  1. ANGLE вызывает `dlopen(NULL)`; в libhybris нет главной программы Android (`somain` = 0xdeadbeef) — падение. Решение: особый дескриптор «вся программа» (dlsym ищет везде, dlclose игнорирует).
  2. ANGLE несёт свою libc++abi и экспортирует её; как корень группы она перекрывала `__cxa_throw` системной libc++.so — `std::bad_cast` без обработчика и завершение Factorio. Решение: символы библиотек `*_angle.so` видны только им самим.
- Первая попытка «dlopen(NULL) → первая библиотека списка» сломала dlsym у обычного libEGL: первой в списке была сама libEGL.
  3. (v54) Выход потока, который вызывал EGL (поток сервера после удаления первого контекста 32×32): ключ TLS libhybris создан раньше ключа ANGLE, поэтому его деструктор освобождал данные потока Android раньше, и `egl::ReleaseThread` ANGLE падал (SIGSEGV через 0,7 с на телефоне, чёрный экран). `eglReleaseThread()` перед выходом не помогает: ключ ANGLE остаётся. Решение в bionic_tls.c: деструктор libhybris возвращает своё значение до последнего круга `PTHREAD_DESTRUCTOR_ITERATIONS`.
- На стенде этот сбой не виден в синхронном режиме (`SYNC=1`): там поток не завершается. Проверять ANGLE и в режиме с потоками.
- Правка: app/src/main/cpp/gladio/direct/hybris-linker-angle.patch (накладывает tools/build-gladio-direct.sh). Подтверждение: стенд, Factorio меню через ANGLE (Vulkan Adreno 830), обычный драйвер с тем же q.so тоже рисует. Отладка: gdb + add-symbol-file для библиотек /system (адреса из /proc/PID/maps).

## Стенд proot (/root/builds/gladio-direct/run)
- FPS и время на стенде не показательны: futex и clock_gettime идут через ptrace. Число вызовов за кадр и картинка — показательны.
- `perf_event_open` запрещён (SELinux). Профилировщик по SIGPROF: /root/builds/gladio-direct/prof (sampler.c, analyze.py; символы glibc из пакета libc6-dbg).
- `pgrep -f <имя>` находит и сам цикл ожидания, в командной строке которого есть это имя. Ждать игру через `wait` или по PID.

## Git и GitHub (Fagarester/Factorio_port-50-50)
- 2026-11-06. `/root/factory` — рабочая копия репозитория. Termux:X11 — подмодуль на e8e9847; наши изменения только в `third_party/patches/termux-x11` (накладывает `tools/setup.sh`).
- Сборка Termux:X11 сама меняет файлы вложенных подмодулей (`target_apply_patch` в `lorie/src/main/cpp/recipes/*.cmake`). Это не наши правки; локально стоит `submodule.third_party/termux-x11.ignore=dirty`.
- Готовые `runtime.tar.xz` и APK не коммитятся (`.gitignore`), а выкладываются в Releases. Лимит GitHub на файл в репозитории — 100 МБ.
- Подтверждение: чистый клон + `tools/setup.sh` + патчи CMake дали дерево, идентичное резервной копии №10 (diff -rq пуст, кроме документов и сборочных файлов).
- 2026-11-06. GitHub Actions (`.github/workflows/build.yml`) собирает debug+release за ~6 мин. Подпись — секреты `FACTORY_*`: копия `~/.android/debug.keystore` со случайным паролем (сертификат тот же, SHA-256 7d2f4119…), поэтому APK из Actions ставится поверх телефонной сборки (проверено по подписи, запуск 42293330791). Пароль `android` в секретах не использовать: GitHub маскирует это слово во всех журналах. Пароль копии нигде не сохранён; при потере — заново сделать копию и обновить секреты.
- Release-сборку останавливал `lintVitalRelease` (`ExpiredTargetSdkVersion`, targetSdk 29); проверка отключена в app/build.gradle.

## Архив игры с factorio.com (первый запуск, v56)
- 2026-11-08. `factorio-space-age_linux-arm64_2.1.24.tar.xz`: 4,4 ГБ, внутри одна папка `factorio/`, 23 263 записи, 5,1 ГБ после распаковки. Первая запись — `factorio/bin/arm64/factorio`.
- Логотипы для экрана загрузки идут в середине потока: `data/core/graphics/background-image-logo.png` после ~1,75 ГБ (34 %), `data/space-age/graphics/space-age-logo-spritesheet.png` (16 кадров друг под другом, 1852×2944) после ~2,97 ГБ (58 %). Достать их раньше можно только распаковав поток до этого места, поэтому экран показывает их по ходу распаковки.
- Подтверждение: `xz -dc … | tar -tv` по архиву пользователя в Download.
- 2026-11-09. Архив — один блок xz (`xz -lv`): до логотипа нельзя дойти, не распаковав всё перед ним. v75 делал отдельное первое чтение ради логотипов; пользователь отклонил: двойная работа, распаковка дольше (без DLC логотип на ~81 % данных). v76 вернул показ логотипов по ходу одной распаковки. Не повторять двухпроходную схему.

## Разрешения Android 13+ (оповещения убраны в v61)
- 2026-11-08. При targetSdk 29 приложение не может само задать вопрос POST_NOTIFICATIONS: `requestPermissions` в MainActivity не показал окна (видео пользователя, v60). Android спрашивает сам, когда открывается экран после создания канала, то есть над стартующей игрой (чёрный экран 2–4 с, звук уже идёт). Попытки v58 (канал в FirstRun) и v59/v60 (запрос) не помогли — заменены.
- Главная причина (v63 screen.log, 2026-11-08): `com.termux.x11.MainActivity.onCreate` сам вызывает `requestPermissions(POST_NOTIFICATIONS)`; Android открывает экран разрешений поверх игры (экран игры paused 1,0 → resumed 1,3 с даже без окна вопроса). Убрано патчем `third_party/patches/termux-x11/0010-no-notification-question.patch` (v64).
- Решение v61: `POST_NOTIFICATIONS` убрано из манифеста (`tools:node="remove"`, его объявлял Termux:X11). Служба игры работает и без него, только её уведомление не видно.
- Текст вопроса о файлах зависит от targetSdk: 28 (старый порт r34) — «фото, видео, аудио и другим файлам», 29 (Factory) — «фото, видео, музыке и аудио». Разрешения одинаковые (READ/WRITE_EXTERNAL_STORAGE).

## Gladio: большие ответы сервера (артефакты на Mali, v67)
- 2026-11-08. На Mali-G610 (тестер, Gladio «напрямую», без S3TC) деревья в сетку при отдалении. В game.log: `ring: buffer overflow on write (67108864/33554432)` перед каждым «Generated mipmaps». Factorio читает атлас `glGetTexImage` 4096×4096 RGBA = 64 МБ; кольцо ответов 32 МБ — данные не доходили, мипмапы из мусора.
- Размер чтения задаёт обработчик текстур Factorio: `Texture processor created (4096)` при `max-texture-size=8192` + `texture-streaming=false` (у тестера), `(2048)` при обычном конфиге стенда — там ошибки нет. Adreno не затронут: S3TC остаются сжатыми.
- Решение: RingBuffer_read/write в обеих копиях (`runtime/gladio/src`, `app/src/main/cpp/gladio/common/src`) передают больше половины кольца частями; `glReadPixels` без PBO больше половины кольца — через память и частями (иначе клиент ждал бы вечно).
- Журнал: `rings: …` в начале, `… KB reply, in parts` (сервер), `ring: read/write … in parts`, сторож `ring: … waits N s` для ожиданий >4 КБ.
- Подтверждение: стенд `/root/builds/gladio-direct/run`, `GLADIO_BC=0`, config-mtk (max-texture-size=8192, texture-streaming=false, video-memory-usage=medium): до — 32 строки overflow, после — 0, загрузка 77–78 с в режимах потоков и SYNC=1.

## Ориентация экрана при поиске и копировании игры (v77–v79)
- 2026-11-09. Альбомный режим держался только при самом первом запуске (`MainActivity.isFirstStart`); `hideStartupCover` его снимал. После сброса данных окно «Где игра?» и экран распаковки шли в портрете (скриншоты пользователя). С v79 `MainActivity.lockLandscape()` вызывают `findGame`, `showGameSource`, `progressScreen`; снимает только «Назад» в окне выбора.
- MainActivity не пересоздаётся при повороте (`configChanges` с orientation), поэтому смена ориентации не обрывает распаковку. Но экран загрузки, открытый до конца поворота, мерил ширину по портрету — теперь при закреплённой ориентации берётся ширина альбомной.

## Логотип с factorio.com: IPv6 (v80–v82)
- 2026-11-09. Логотип появлялся на ~32 % распаковки. launcher.log v81: DNS 1,7 с, `connected in 30344 ms`, сам ответ ~0,4 с. DNS отдаёт сначала 6 адресов IPv6 cdn.factorio.com, на Wi-Fi пользователя IPv6 не работает (`curl -6` — таймаут, `curl -4` — 84 мс). HttpURLConnection пробует адреса по очереди: 6 × 5 с таймаута. Браузер пробует параллельно, поэтому быстрый.
- Решение v82: `core/QuickHttps` — соединение со всеми адресами сразу, первый ответивший; HTTPS через SSLSocket с проверкой имени (`setEndpointIdentificationAlgorithm("HTTPS")` + проверка Android). Стенд JDK (тот же Wi-Fi): 0,3–0,8 с; badssl self-signed/wrong.host/expired отклонены (/root/builds/quickhttps-test).
- Подтверждено на телефоне (v82, launcher.log пользователя): логотип на экране через 1,98 с (DNS 1,69 с, соединение 36 мс). Журнал `LauncherLog` убран в v83 по просьбе пользователя; для новой диагностики — добавить заново.

## Демо с factorio.com (v84)
- 2026-11-09. `https://www.factorio.com/get-download/stable/demo/linux-arm64` → 302 на dl.factorio.com (`factorio-demo_linux-arm64_<версия>.tar.xz`, 2.1.23: 1 407 563 136 байт), без входа в аккаунт. Range работает (206), ссылка с `secure=` временная — при докачке брать заново. У dl.factorio.com тоже есть IPv6 — качать через `QuickHttps`.
- Архив демо устроен как полный: `factorio/bin/arm64/factorio`, только data/base и core. `factorio --version` → «2.1.23 … linux-arm64, demo».
- Подтверждение: стенд JDK (копия `fetchDemo` в /root/builds/quickhttps-test/src/T3.java): отмена на 10 %, докачка, `xz -t` целого архива — ок, 38 с на Wi-Fi.
