# Contributing

Thanks for taking the time to contribute to this project.

## General guidelines

- Keep patches focused and easy to review.
- Prefer small, well-documented changes.
- Document any native build requirement or environment dependency.
- If a change affects Android packaging, graphics drivers, or input emulation, mention the impact clearly in the pull request.

## Project structure

- `app/` – Android app and Gradle files
- `fake-udev/` – virtual udev/gamepad compatibility layer
- `input-interposer/` – input interception library
- `android_alsa/` – ALSA compatibility code
- `glibc_patches/` – glibc patches
- `turnip-patches/` – Turnip-related patches

## Before opening a pull request

1. Make sure the relevant build can at least start in the expected environment.
2. Keep the commit history readable.
3. Include a brief summary of why the change is needed.
4. If you changed build instructions or project setup, update the documentation accordingly.

## Reporting issues

When opening an issue, include:

- what you expected to happen
- what actually happened
- relevant environment details (Android version, SDK/NDK version, host OS, build command)
- logs or stack traces if available

## License

By contributing, you agree that your changes will be released under the same license as this project.
