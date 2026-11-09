# Factory

**English** · [Русский](README.ru.md)

[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#install)
[![ABI arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-blue)](#install)
[![License GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-informational)](LICENSE)

**Factory runs your own copy of Factorio on an Android phone.** It starts the official Factorio build for
Linux ARM64. There is no x86 emulation and no streaming. The app contains no game files.

> **Made by AI.** The code, the documentation and this text were written by Claude (Anthropic's AI model)
> under the direction of [Fagarester](https://github.com/Fagarester), who set the goals, tested every build on
> real phones and decided what stays. This English text was written and translated by AI.

## Status: third iteration

| Iteration | Purpose |
|---|---|
| 1 | Proof that Factorio for Linux ARM64 runs on Android at all |
| 2 | Test ground: own menus, on-screen controls, drivers |
| **3 (this repository)** | The same test ground, as a hybrid of two launchers |

Factory is a hybrid:

- the graphics stack and the Linux runtime come from [Pocket Foundry](https://github.com/JulioFerrero/pocket-foundry):
  glibc built for Android, Mesa, SDL and the Termux:X11 X server;
- the interface comes from the earlier iterations: menus, on-screen controls and their editor;
- **Gladio**, an OpenGL path taken from Winlator and reworked: the game sends its OpenGL calls to a server in the app,
  and the server draws with the phone's GLES (also through the system ANGLE).

**Still to do:** the on-screen controls need more work.

## Install

1. Download the APK from [Releases](../../releases) and install it.
2. Open Factory. On the first start it unpacks its system files and looks for the game in `Download`:
   an unpacked game folder or the archive `factorio…linux…arm64….tar.xz` from
   [factorio.com](https://factorio.com/download).
3. If there is no game, a screen offers three ways: the game folder, the game archive, or the **free demo**.
   The app downloads the demo from factorio.com by itself (about 1.4 GB, no account needed).

You need Android 10 or newer and an ARM64 processor. Factorio is not included: buy it on
[factorio.com](https://factorio.com) and download the **Linux (ARM64)** build, or try the free demo.

## Tested phones

| SoC | GPU | Notes |
|---|---|---|
| Qualcomm Snapdragon 8 Elite | Adreno 830 | Main development phone (Android 16). All drivers. |
| MediaTek Dimensity 9300+ | Immortalis-G720 MC12 | Tester's phone. Gladio. |
| MediaTek (exact model not recorded) | Mali-G610 MC4 | Tester's phone. Gladio. |

Only these three phones were tested.

## It doesn't work on my phone

That is likely: three phones are all the testing there is. You are welcome to fork the repository and make it work
on yours. Pull requests and issues with logs are welcome too. The app copies its logs to `Documents/Factory`.

## Graphics drivers

The driver is chosen in the app's settings. On the first start the app picks one by the GPU: Turnip for Adreno 600
and newer, Gladio for anything else.

| Block | Choices | How it draws |
|---|---|---|
| Turnip | Turnip, Freedreno or an imported Vulkan driver | OpenGL through Zink on Vulkan; Freedreno: OpenGL from Mesa |
| Gladio | Display: Termux:X11 or the app's own X server | OpenGL → Gladio server → the phone's GLES |
| Gladio, Termux:X11 only | "Phone's driver directly", "ANGLE" (each or both) | Server inside the game's process through libhybris |

## Other features

- On-screen controls with an editor, five bundled layouts, and button icons taken from **your own** copy of the game.
- Graphics presets, optional texture compression, and a built-in benchmark of the vanilla menu scenes.
- Loading screens with the game's logo: from your game files, or from factorio.com while an archive is unpacked.

## Build

Short version:

```sh
git clone --recursive https://github.com/Fagarester/Factorio_port-50-50.git
tools/setup.sh                               # Termux:X11 and the project's patches
runtime/build.sh glibc mesa sdl wm assemble  # the Linux runtime (Docker); or take runtime.tar.xz from a release
python3 tools/repack-runtime.py runtime/out/apk/assets/runtime.tar.xz \
    runtime/out-factory/assets/runtime.tar.xz com.factory
./gradlew :app:assembleDebug
```

GitHub Actions builds the APK too (`.github/workflows/build.yml`). Details (in Russian):
[docs/building.md](docs/building.md).

## Layout

| Path | Contents |
|---|---|
| `app/src/main/java/com/factory` | Interface, controls, first start, game launch |
| `app/src/main/java/com/factory/runtime` | Session, paths, input and audio bridges (from Pocket Foundry) |
| `app/src/main/cpp/gladio` | Gladio server and the "direct" mode |
| `runtime/` | Builds of glibc, Mesa, SDL, the window manager and the Gladio client |
| `third_party/termux-x11` | Termux:X11 submodule; our patches are in `third_party/patches` |
| `tools/` | Build and preparation scripts |
| `LEARNING.md` | Confirmed lessons from development (in Russian) |

## Thanks

- **[Wube Software](https://www.factorio.com)** for Factorio, and above all for the official **Linux ARM64** build.
  Without it none of this would exist.
- **[JulioFerrero / Pocket Foundry](https://github.com/JulioFerrero/pocket-foundry)**, the neighbouring project:
  the Linux runtime, the graphics stack and the Kotlin session code.
- **[Winlator](https://github.com/brunodev85/winlator)** by brunodev85: Gladio, the built-in X server and the base of
  the earlier iterations' interface.
- **[Termux:X11](https://github.com/termux/termux-x11)**: the X server.
- **[Mesa](https://mesa3d.org)** (Turnip, Freedreno, Zink), **glibc**, **[SDL](https://libsdl.org)**,
  **[libhybris](https://github.com/libhybris/libhybris)**, **XZ Utils**, **Apache Commons Compress**, **AndroidX**,
  **stb_dxt**, **Material Design icons** and the **Titillium Web** font.
- The testers from the Factorio-on-Android community who ran builds on their MediaTek phones and sent logs.
- **Claude** by [Anthropic](https://www.anthropic.com), which wrote the code under Fagarester's direction.

## License

GPL-3.0, like Termux:X11 and Pocket Foundry. Code taken from Winlator keeps its LGPL-2.1 license, which allows its
use in a GPL-3.0 project. The app also contains glibc (LGPL-2.1), Mesa (MIT),
SDL (zlib), liblzma (0BSD), libhybris (Apache-2.0), Material Design icons (Apache-2.0), the Titillium Web font
(OFL-1.1), Apache Commons Compress and AndroidX (Apache-2.0).

Factorio is © Wube Software. Factory is not affiliated with Wube Software and contains no game files.
