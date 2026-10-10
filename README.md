# Factorio (Android port)

Fork of Winlator that runs the native ARM64 build of Factorio on Android
(glibc rootfs + Turnip/Zink, Selkies input interposer for gamepads).

- Package / applicationId: `com.factorio` (was `com.winlator`)
- Build: open `app/` in Android Studio (AGP 7.2.2, NDK 24.0.8215888, ABI arm64-v8a)
- Game folder: put Factorio (Linux arm64 build) into `Download/factorio` on the phone, then start the app.

## Package name note
All prebuilt archives (`rootfs.tzst`, drivers, box64, container pattern) contain absolute paths
`/data/data/com.factorio/files/rootfs/...`. `com.factorio` has exactly the same length as the old
`com.winlator`, so the paths were patched in place. If you ever change the package again, pick a name
of the same length or rebuild those archives (sources: `vortek/`, `gladio/`, `android_alsa/`, `glibc_patches/`).

## Credits
Winlator by brunodev85, GLIBC patches by Termux Pacman, Box64 by ptitSeb, Mesa, DXVK, VKD3D, Wine.
Licensed under GPL-3.0 (see LICENSE).
