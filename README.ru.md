# Factory

[English](README.md) · **Русский**

[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#установка)
[![ABI arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-blue)](#установка)
[![License GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-informational)](LICENSE)

**Factory запускает вашу копию Factorio на Android.** На телефоне работает официальная сборка Factorio для Linux ARM64.
Эмуляции и стриминга нет. Файлов игры в приложении нет.

> **Неофициально.** Factory — любительский проект. Его не создавала, не одобряла и не поддерживает Wube Software,
> разработчик Factorio. Пожалуйста, не пишите в Wube о проблемах Factory. Мы собираемся написать разработчикам
> Factorio о проекте и попросить их одобрения; ответ будет указан здесь.

## Третья итерация

| Итерация | Что это |
|---|---|
| 1 | Проверка, что Factorio для Linux ARM64 вообще работает на Android |
| 2 | Тестовый полигон на основе Winlator: вырезаны Wine, Box64, FEX и всё ненужное; остались дизайн интерфейса, настройки и экранное управление |
| **3 (этот репозиторий)** | Тот же полигон, но гибрид: интерфейс второй итерации на среде выполнения Pocket Foundry. Gladio дописан и переработан (режим «напрямую», ANGLE, пакетная передача, большие данные по частям), в экранное управление добавлены новые виды элементов: радиальное меню, колесо прокрутки, боковая шторка и своё меню |

Осталось доработать экранное управление. Проект создан Claude (ИИ от Anthropic) под руководством Fagarester.

Factory — гибрид двух лаунчеров:

- из [Pocket Foundry](https://github.com/JulioFerrero/pocket-foundry) (соседний проект): среда выполнения Linux
  (glibc для Android, Mesa, SDL, оконный менеджер), код игровой сессии и вывод через Termux:X11;
- из [Winlator](https://github.com/brunodev85/winlator) через прошлые итерации: меню настроек, экранное управление
  и его редактор, встроенный X-сервер приложения и **Gladio** — переработанный здесь путь OpenGL: игра передаёт вызовы
  OpenGL серверу внутри приложения, сервер рисует через GLES телефона (в том числе через системный ANGLE).

## Установка

1. Скачайте APK из [Releases](../../releases) и установите.
2. Откройте Factory. При первом запуске приложение ставит системные файлы и ищет игру в `Download`:
   папку с распакованной игрой или архив `factorio…linux…arm64….tar.xz` с [factorio.com](https://factorio.com/download).
3. Если игры нет, откроется экран выбора: папка с игрой, архив игры или **бесплатная демо-версия**
   (приложение само скачает её с factorio.com, около 1,4 ГБ, без регистрации).

Нужен Android 10 или новее и процессор ARM64. Подробности, благодарности и список проверенных телефонов —
в [английском README](README.md).

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
