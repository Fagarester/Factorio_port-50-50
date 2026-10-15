# Что изменено в этой версии

1. **Workflow** `.github/workflows/build.yml`: имя `Build Factorio`, артефакт `Factorio-debug`.
2. **Боковая шторка в игре**: «Настройки» стоят над «Выход», «Выход» — отдельная кнопка, прижатая к самому низу шторки
   (`res/layout/xserver_display_activity.xml`, `res/menu/xserver_menu.xml`).
3. **Геймпад ↔ экранное управление** (`XServerDisplayActivity`): геймпад подключили — экран управления скрывается,
   отключили — появляется. В диалоге «Управление» есть галочка «Показывать экран управления при подключённом геймпаде»
   (сохраняется), с ней экранное управление остаётся и при геймпаде.
4. **Драйверы** — единый реестр `core/DriverPackages.java`:
   - один пункт «Установить драйвер из файла» (Настройки → Графический драйвер), тип определяется по содержимому файла:
     `.zip` с `meta.json` → bionic-драйвер (Mali PanVK, Adreno, Xclipse...), грузится через Vortek+adrenotools;
     `.tzst` → сборка Turnip для proot-rootfs (glibc). Любой драйвер можно выбрать на любом устройстве.
   - список Vulkan: Turnip / Vortek (системный) / Auto / все установленные драйверы. Выбор драйвера сам выставляет
     рендерер и конфиг. Формат хранения в контейнере не менялся.
   - «Auto» = лучший установленный драйвер под GPU устройства (Adreno/Mali/Xclipse/PowerVR определяются по GL_RENDERER).
     Семейство драйвера берётся из `meta.json` (`gpuFamily`/`gpuFamilies`) или определяется по backend/имени.
     Несовпадение с GPU — только предупреждение, не запрет.
   - Исправлено: импорт читал первый попавшийся `*.json` (у zenithblue это MANIFEST.json без libraryName) и брал путь
     только для основной памяти (Загрузки/Telegram молча не работали). Теперь берётся `meta.json`, файл читается по Uri.
   - Turnip тоже заменяемый: кнопка установки в диалоге Turnip → «Из файла» (имя версии берётся из имени файла).
   - proot/glibc-структура не тронута.

5. **VirGL и Gladio убраны** как выбираемые драйверы (код, диалог, ассеты, нативный virglrenderer). Нативная библиотека
   `gladiorenderer` оставлена: через неё работает серверная часть GLX (`GLXExtension`).
6. **Zink сменный, как Turnip.** Встроенный Zink 26.2.0 и Turnip 26.2.0 остаются «рабочими по умолчанию».
   Собранные вами пакеты ставятся кнопкой «Установить драйвер из файла»: тип определяется по содержимому `.tzst`
   (`libvulkan_freedreno.so` -> Turnip, `libGL.so*` -> Zink). Версию Zink выбирает кнопка настроек рядом с OpenGL.
7. Добавлен workflow `build-turnip.yml` (Turnip для вашего rootfs, с маркером пути и недостающими библиотеками xcb).
8. **driver-report.txt** (Documents/Factorio): при каждом старте пишет, какие файлы драйверов реально лежат в rootfs
   (размер, строка `Mesa x.y.z (git-...)` из самой библиотеки), чтобы отличать новый драйвер от старого.
9. **Экспериментальный Turnip** - `.github/workflows/build-turnip-experimental.yml` + папка `turnip-patches/`
   (рецепт и патчи из проекта Banners-Turnip, GPL-3.0): Mesa main + патчи Adreno 8xx. Ставится рядом отдельной версией
   вида `26.3.0-a8xx-<коммит>`, рабочие 26.2.0/26.2.4 не затрагиваются.


## v22 — исправление зависания нативного GLX (glXCreateContext)
- Причина: запрос GLXCreateContext (24 байта) разбирался как 21 байт; 3 байта выравнивания оставались в потоке
  и следующий запрос читался со сдвига, поэтому сервер переставал отвечать клиенту, а клиент вечно ждал в poll()
  на сокете X (подтверждено watchdog-ом glxtest: главный поток в ppoll на X-сокете).
  Zink-путь это не затрагивало, потому что он использует glXCreateContextAttribsARB, который читается целиком.
- XClientRequestHandler: после обработчика расширения пропускаются непрочитанные байты запроса
  (в debug-лог пишется "ext request left N unread byte(s)").
- XClientRequestHandler: запрос к неизвестному расширению теперь возвращает ошибку, а не молча игнорируется.
- GLXExtension.createContext: убран ответ — по протоколу GLX у CreateContext ответа нет.

## v23 — GLX: ответ на IsDirect и безопасные заглушки
- После исправления зависания (v22) glXCreateContext стал возвращаться, но Mesa сразу шлёт GLXIsDirect (opcode 6)
  и считает контекст неудачным, если получает ошибку (лог: "GLXTEST: FAIL glXCreateContext").
  Теперь сервер отвечает isDirect = 1.
- GLXMakeCurrent (5) и GLXMakeContextCurrent (26) отвечают ненулевым contextTag.
- GLXClientInfo (20), SetClientInfoARB (33), SetClientInfo2ARB (35) принимаются и игнорируются
  (раньше в ответ приходил X Error BadImplementation).

## v24 — DRI3: диагностика буфера кадра, GLX GetDrawableAttributes
- Нативный Freedreno/KGSL уже создаёт контекст и отдаёт GL_RENDERER = Adreno (TM) 830, OpenGL 4.6 (glxtest после v23).
- Остался сбой DRI3: PixmapFromBuffer завершается BadAlloc (X error 11), потому что mmap() fd от клиента не удался.
  DRI3Extension.pixmapFromFd теперь пишет в debug-лог, что за fd пришёл (ссылка /proc/self/fd, fdinfo, размеры),
  и при неудаче mmap только для чтения пробует чтение/запись.
- GLXGetDrawableAttributes (29): ответ с пустым списком атрибутов (раньше X Error BadImplementation).

## v25 — DRI3: размер буфера кадра
- Диагностика v24 показала: клиент присылает dma-buf (exp_name: system, 1 МиБ) и в запросе PixmapFromBuffer указывает size=0,
  поэтому mmap(длина 0) всегда падал (BadAlloc). Теперь размер берётся как stride*height, если клиент прислал меньше.

## v26 — GLX: у CreateContextAttribsARB нет ответа
- Factorio (SDL) падал на создании контекста: "xcb_request_check: Assertion `!reply' failed". Причина: запрос
  GLXCreateContextAttribsARB (opcode 34) обрабатывался с ответом из 32 байт, но у него ответа нет по протоколу;
  xcb_request_check() в клиенте на лишний ответ делает abort().
- Ответ убран. Заодно снято ограничение версии GL <= 3.3 (контекст рисует сам клиент; на сервере только заглушка).
  Запрошенная версия пишется в debug-лог ("GLX createContextAttribsARB: requested GL x.y").

## v27 — метка сборки
- Первый GLX-запрос пишет в debug-лог строку "BUILD v27: ext-unread-skip, glx-createctx-noreply, glx-attribs-noreply,
  glx-isdirect, dri3-size-fix". Если этой строки нет в logs.txt, в APK нет всех исправлений.

## v28 — нативный OpenGL: синхронизация буфера кадра (кубики и мерцание)
- Игра в нативном GL запустилась (заставка с прогрессом), но картинка "сыпалась кубиками" и мигала.
  Вероятные причины: (1) CPU читает dma-buf, который писал GPU, с устаревшим кэшем процессора;
  (2) кадр копируется, пока GPU ещё дорисовывает бины (тайлы Adreno).
- Native: SysVSharedMemory.dmabufSync(fd, start) -> ioctl DMA_BUF_IOCTL_SYNC (READ, START/END).
- DRI3Extension: fd dma-buf не закрывается, а хранится в Drawable.syncFd и закрывается при уничтожении пикскарты.
- PresentExtension: чтение буфера обёрнуто в DMA_BUF_IOCTL_SYNC; в лог пишется "PRESENT #N ... sampled non-zero pixels";
  полностью нулевой dma-buf кадр (не больше 3 подряд) пропускается, чтобы не мигало чёрным (только нативный GL).
- Метка сборки: BUILD v28.
- build-opengl.yml: новый параметр swap_finish (по умолчанию включён) — патч Mesa: glFinish() перед glXSwapBuffers.

## v29 — нативный GL: диагностика ввода и запасной фокус
- В нативном режиме игра рисуется, но клики и клавиши не доходят (в Zink работало). В коде сервера фокус клавиатуры ставится
  только окну, у которого isApplicationWindow() (есть WM_NAME и WM_HINTS.window_group == id окна).
- DesktopHelper: запасной фокус — если фокуса нет или он на корневом окне, верхнему окну с WM_CLASS фокус отдаётся и без этих условий.
  В лог пишутся строки "FOCUS: ..." (какие окна мапятся, их name/class, кому отдан фокус).
- InputDeviceManager: в лог пишутся строки "INPUT: ..." — почему нажатие/клик отброшены (нет фокуса, нет слушателя, окно выключено)
  или кому первые 8 событий доставлены.
- Метка сборки: BUILD v29.
