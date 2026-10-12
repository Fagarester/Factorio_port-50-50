# Factorio (Android, native ARM64)

Форк Winlator, оставленный только ради запуска **нативного ARM64-билда Factorio** на Android
(glibc rootfs + Turnip/Zink/Vortek, мост геймпадов Selkies input interposer).
Wine, Box64, DXVK, VKD3D, WineD3D, Win-компоненты, ярлыки, MIDI и файловый менеджер вырезаны.

*Fork of Winlator reduced to run the native ARM64 build of Factorio. Wine/Box64/DXVK/VKD3D/etc. are removed.*

## Интерфейс
* Один встроенный контейнер (создаётся автоматически при первом запуске), списка контейнеров нет.
* Единые **Настройки** (контейнер: размер экрана, драйверы, звук, переменные среды + мышь, геймпады, язык/тема, логи):
  пункт в самом низу боковой шторки в игре и в главном меню приложения, а также долгое нажатие на значок приложения.
* После сохранения из игры предлагается перезапуск, чтобы настройки применились.

## Сборка
Открыть `app/` в Android Studio (AGP 7.2.2, NDK 24.0.8215888, ABI arm64-v8a). Игру положить в
`Download/factorio` (Linux arm64 билд) — при первом запуске она копируется в rootfs.

## Имя пакета больше не зашито
Любое имя пакета работает **без пересборки rootfs и драйверов**:

* в архивах (`rootfs.tzst`, `graphics_driver/*.tzst`, `installable_components/`) вместо
  `/data/data/<пакет>/files/rootfs` лежит маркер `/data/data/@@@@@@@@@@@@@@@@@@@@@@@@@` (ровно 36 байт);
* при распаковке (`core/PackagePath.java`, вызывается из `TarCompressorUtils`) маркер заменяется на
  `/data/data/<пакет>/r///…` (добивается `/` до 36 байт — повторные слэши в путях безвредны);
  `<dataDir>/r` — симлинк на настоящий `files/rootfs`, его создаёт `App.onCreate`;
* нативный код берёт имя пакета из `/proc/self/cmdline` (`getAppCacheDir()` в `winlator.h`);
* Java-пакет классов остаётся `com.factorio` (JNI-имена не меняются), меняется только пакет приложения:
  `applicationId` в `app/app/build.gradle` или `package` в манифесте (MT Manager / apktool).

**Ограничение:** имя пакета — не длиннее **23 символов** (запас в 36-байтовом слоте). Например
`com.antutu.ABenchMark` (21) подходит. Если длиннее — приложение покажет сообщение и закроется.

Старые пути `/data/data/com.winlator/files/rootfs` и `/data/data/com.factorio/files/rootfs` в архивах
тоже распознаются, так что компоненты из апстрима Winlator устанавливаются корректно.

## Пересборка нативных частей гостя
Исходники `vortek/`, `gladio/`, `android_alsa/`, `.github/workflows/*` уже используют маркер как префикс —
собранные библиотеки можно класть в архивы как есть. Маркер должен остаться ровно 36 байт.

## Благодарности
Winlator (brunodev85), GLIBC-патчи Termux Pacman, Mesa, Turnip, PulseAudio. Лицензия GPL-3.0 (LICENSE).
