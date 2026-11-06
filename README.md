# Factory

[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#установка)
[![ABI arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-blue)](#установка)
[![License GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-informational)](LICENSE)

**Factory запускает вашу копию Factorio на Android.** На телефоне работает официальная сборка Factorio для Linux ARM64.
Эмуляции и стриминга нет. Файлов игры в приложении нет.

Factory — гибрид двух лаунчеров:

- графика и среда выполнения из [Pocket Foundry](https://github.com/JulioFerrero/pocket-foundry): glibc для Android,
  Mesa, SDL и X-сервер Termux:X11;
- собственный интерфейс: меню, экранное управление и его редактор, выбор разрешения и сжатия текстур при первом запуске;
- собственный путь отрисовки **Gladio**: игра передаёт вызовы OpenGL серверу внутри приложения, сервер рисует через GLES
  телефона (в том числе через системный ANGLE).

## Установка

1. Скачайте APK из [Releases](../../releases).
2. Купите Factorio и скачайте **Linux (arm64) .tar.xz** с [factorio.com/download](https://factorio.com/download).
3. Распакуйте игру в папку `Download/factorio` на телефоне.
4. Откройте Factory. При первом запуске выберите разрешение и сжатие текстур.

Нужен Android 10 или новее и процессор ARM64.

## Графические драйверы

Драйвер выбирается в настройках приложения.

| Блок | Варианты | Как рисует |
|---|---|---|
| Turnip | Turnip, Freedreno или установленный драйвер Vulkan | OpenGL через Zink поверх Vulkan; для Freedreno — OpenGL из Mesa |
| Gladio | Экран: Termux:X11 или X-сервер приложения | OpenGL → сервер Gladio → GLES телефона |
| Gladio, только с Termux:X11 | «Драйвер телефона напрямую», «ANGLE» (по отдельности или вместе) | Сервер в процессе игры через libhybris |

## Сборка

Коротко:

```sh
tools/setup.sh                               # Termux:X11 и патчи проекта
runtime/build.sh glibc mesa sdl wm assemble  # среда выполнения Linux (Docker)
python3 tools/repack-runtime.py runtime/out/apk/assets/runtime.tar.xz \
    runtime/out-factory/assets/runtime.tar.xz com.factory   # пути среды под пакет com.factory
./gradlew :app:assembleDebug                 # APK
```

Подробности: [docs/building.md](docs/building.md).

## Структура

| Путь | Что там |
|---|---|
| `app/src/main/java/com/factory` | Интерфейс, управление, запуск игры |
| `app/src/main/java/com/factory/runtime` | Сессия, пути, мосты ввода и звука (из Pocket Foundry) |
| `app/src/main/cpp/gladio` | Сервер Gladio и режим «напрямую» |
| `runtime/` | Сборка glibc, Mesa, SDL, оконного менеджера и клиента Gladio |
| `third_party/termux-x11` | Подмодуль Termux:X11 |
| `third_party/patches` | Патчи для Termux:X11 |
| `tools/` | Скрипты сборки и подготовки |
| `LEARNING.md` | Подтверждённые уроки разработки |

## Лицензия

GPL-3.0, как у Termux:X11 и Pocket Foundry. В приложении также есть glibc (LGPL-2.1), Mesa (MIT), SDL (zlib),
liblzma (0BSD), libhybris (Apache-2.0), шрифт Titillium Web (OFL-1.1), Apache Commons Compress и AndroidX (Apache-2.0).

Factorio © Wube Software. Factory не связан с Wube и не содержит файлов игры.
