# QemuVmClient

A robust Android client for hosting and managing QEMU virtual machines on modern Android devices (ARM64).

This project enables you to run full desktop operating systems (like Alpine Linux, Debian, or even Windows) directly on your Android phone using QEMU's system-mode emulation (`qemu-system-aarch64`).

## Features
* **Foreground Service Supervision**: The VM runs inside a standard Android foreground service.
* **Persistent WakeLocks**: Configurable CPU partial wakelocks and screen-dim wakelocks to keep the VM processing seamlessly even when the screen is turned off.
* **Battery Optimization Management**: Directly prompts users to whitelist the app from aggressive Doze modes.
* **Boot Recovery**: Supports auto-starting the VM cleanly after a device reboot.
* **QEMU Native Abstraction**: Generates necessary command-line arguments to bridge QEMU internal state, serial interfaces, SSH, and networking mapping.
* **Jetpack Compose UI**: Simple material control panel for monitoring the VM output, adjusting core configurations, and controlling execution status.

## Target Audience
Advanced users, developers, and makers who want a sandbox Linux or x86 environment running persistently within their ARM64 Android devices without rooting.

## Requirements
* Android 8.0 (API 26) or higher.
* Bundled Android-compatible QEMU binaries and dependencies under `app/src/main/jniLibs/arm64-v8a/`.

## Getting Started

1. Check out the project and open it in Android Studio.
2. Keep the bundled `jniLibs` intact. The app executes `libqemu.so` and `libqemu_img.so` from `nativeLibraryDir` together with their Termux-built dependencies.
3. Optionally, push your firmware (`QEMU_EFI.fd`) and OS images (`.iso`, `.qcow2`, or `.img`) to your device's storage and configure the absolute paths via the Compose UI.
4. Hit **Start VM** and SSH into it from a terminal at `127.0.0.1:2222`.

## Licensing
This project skeleton and UI logic are released under the MIT License. Note that if you bundle QEMU binaries, you must comply with QEMU's GNU GPLv2 licensing.
