# Сборка и устройство

## Как это работает

- **Игра пользователя.** При первом запуске Factory копирует игру из `Download/factorio` в личную папку приложения.
  В APK нет файлов Factorio.
- **Среда выполнения Linux** (`runtime/`). glibc собрана под пути Android, рядом Mesa (Zink, Turnip, Freedreno), SDL 3
  и маленький оконный менеджер. Среда собрана для пакета `app.pocketfoundry`. `tools/repack-runtime.py` меняет пути
  на `com.factory` той же длины, поэтому двоичные файлы остаются рабочими.
- **Экран.** X-сервер Termux:X11 (`third_party/termux-x11`, подмодуль с патчами из `third_party/patches`).
- **Gladio.** Клиент `libGL.so.1` (`runtime/gladio`) передаёт вызовы OpenGL серверу в приложении
  (`app/src/main/cpp/gladio/server`). Сервер рисует через GLES телефона. Режим «напрямую»
  (`app/src/main/cpp/gladio/direct`) загружает системный GLES или ANGLE в процесс игры через libhybris.
- **Интерфейс** (`app/src/main/java/com/factory`): меню, экранное управление, редактор управления, первый запуск.
  Код `com.factory.runtime` взят из Pocket Foundry.

## Подготовка

```sh
git clone --recursive https://github.com/Fagarester/Factorio_port-50-50.git
cd Factorio_port-50-50
tools/setup.sh    # подмодуль Termux:X11 и патчи; можно запускать повторно
```

## Среда выполнения

Вариант 1 — собрать (нужен Docker):

```sh
runtime/build.sh glibc mesa sdl wm assemble
python3 tools/repack-runtime.py runtime/out/apk/assets/runtime.tar.xz \
    runtime/out-factory/assets/runtime.tar.xz com.factory
```

Вариант 2 — взять готовый `runtime.tar.xz` из [Releases](../../../releases) и положить в
`runtime/out-factory/assets/`.

Клиент Gladio и режим «напрямую» собираются отдельно: `tools/build-gladio-client.sh`, `tools/build-gladio-direct.sh`.
Готовые файлы уже лежат в `app/src/main/assets/gladio*`.

## APK

```sh
./gradlew :app:assembleDebug
```

Нужны JDK 17, Android SDK (compileSdk 36) и NDK 29.0.14206865. На ARM64 в proot используется `build.sh.local`:
он подставляет системный aapt2 и пути этой машины.

Подпись: если есть `../../factory-keys/keystore.properties` (или переменная `FACTORY_SIGNING` указывает на файл),
debug и release подписываются этим ключом. Иначе используется обычный debug-ключ. Ключи в репозиторий не кладутся.
