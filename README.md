# Factorio Port 50-50

This repository contains the Android-side porting assets and support libraries for running a Factorio build on Android. It includes the Android app project, native build integration, and helper libraries for input and device emulation.

The project is based on the upstream Factorio Android port and includes additional components for compatibility, gamepad emulation, and graphics driver patches.

## Repository layout

- `app/` – Android project and Gradle build files for the app
- `android_alsa/` – ALSA-related Android compatibility layer
- `fake-udev/` – virtual udev/gamepad shim used for SDL and gamepad detection
- `input-interposer/` – input interposer library for joystick/gamepad redirection
- `glibc_patches/` – glibc compatibility patches
- `turnip-patches/` – Turnip/graphics stack patches
- `.github/workflows/` – CI workflows for gamepad, OpenGL, Turnip, and Zink builds
- `LICENSE` – project license
- `README.md` – this file

## What this repository is for

This repository is not a single packaged APK by itself. Instead, it provides the project structure and supporting binaries/source needed for building the Android app and native dependencies required by the game port.

The build system appears to be designed around:

- Android Gradle builds for the application layer
- Native C/C++ builds for the game runtime and compatibility hooks
- CI jobs for building OpenGL and graphics-related libraries
- Gamepad/input compatibility layers for Linux and Android environments

## Quick start

### Prerequisites

Before building the project, make sure you have:

- Android SDK / NDK configured
- Java 11+ and Gradle
- CMake and a compatible native toolchain
- A Linux-based environment for native component builds

### Android app build

From the `app/` directory:

```bash
cd app
./gradlew assembleDebug
```

If you are building against a prebuilt native directory, the project supports a property such as:

```bash
./gradlew -PfactorioPrebuiltNativeDir=/path/to/prebuilt/native assembleDebug
```

## Native helper components

The repository contains several subprojects that are important for compatibility:

- `fake-udev` prepares a virtual libudev environment for gamepad detection
- `input-interposer` intercepts device open calls and routes input through sockets
- `android_alsa` and the patched graphics libraries support Android-specific runtime behavior

For more detail, see each subdirectory’s README or source comments.

## CI and automation

The repository includes GitHub Actions workflows to build gamepad libraries and graphics stacks, including:

- `build-gamepad-libs.yml`
- `build-opengl.yml`
- `build-turnip.yml`
- `build-turnip-experimental.yml`
- `build-zink.yml`
- `build.yml`

These workflows are useful for packaging and validating relevant native components before running or shipping the Android app.

## Development notes

- The project uses a mix of Android app files and native C/C++ support code.
- Some components are Linux- or libudev-oriented and are meant to emulate input devices in constrained environments.
- If you are working locally, keep an eye on the native toolchain and Android SDK/NDK versions, since the app explicitly pins a specific NDK version in the Gradle config.

## Contributing

Contributions are welcome.

Before submitting changes:

- keep the project structure organized
- document native build requirements clearly
- test Android app builds and native builds where possible
- keep changes scoped to the relevant component

## License

This project is licensed under the GNU Lesser General Public License v2.1. See `LICENSE` for the full text.

## Related information

This repo is a fork of the upstream project and includes Android porting and compatibility work for native dependencies. If you are debugging build issues, start with the Android Gradle project under `app/` and then inspect the helper libraries in the root-level folders.
