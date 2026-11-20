# NothingBlurShortcuts 🪟🔒✨

[![Nothing OS](https://img.shields.io/badge/Nothing%20OS-5.0-black?style=for-the-badge&logo=android)](https://nothing.tech)
[![LSPosed](https://img.shields.io/badge/LSPosed-Supported-5C6BC0?style=for-the-badge)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=for-the-badge)](LICENSE)

An **Xposed / LSPosed** module for Nothing OS that transforms the two lock screen shortcut buttons (Affordances: Flashlight, Camera, Wallet, Controls, etc.) into authentic **frosted glass widgets** with live hardware-accelerated wallpaper blur — matching official Nothing OS lockscreen widgets and [NothingBlurIcons](https://github.com/Ak1o-T/NothingBlurIcons).

[English](#english) | [Русский](#русский)

---

# English

## 💡 Concept & Architecture

On Nothing OS lockscreen:
- **Lockscreen Widgets** (Clock, Weather, Media Player, Quick Actions) feature authentic Frosted Glass wallpaper blur with a delicate specular ring and subtle tint.
- **Lockscreen Shortcut Buttons** (Bottom left & bottom right quick affordances) by default only have a static, flat, semi-opaque circular background without any wallpaper blur.

**Why a KernelSU / RRO Overlay is NOT enough:**
A pure Magisk or KernelSU overlay (Runtime Resource Overlay) can only modify static drawables, colors, or XML vectors. True dynamic wallpaper blur in Android requires:
1. Real-time access to the lockscreen wallpaper backdrop provider (`FrostedBackdropProvider` / `NTKeyguardFrostedBackdrop`).
2. Coordinate projection (`updateOffset(targetX, targetY)`) tracking button movement during pull-down animations and shade expansion.
3. Live shader rendering through Nothing OS's proprietary `FrostedBackdropDrawable`.

**How NothingBlurShortcuts Works:**
- **Target Process:** `com.android.systemui`
- **Target Views:** `com.android.systemui.statusbar.phone.KeyguardBottomAreaView` -> `@id/start_button` & `@id/end_button` (`com.android.systemui.animation.view.LaunchableImageView`).
- **Hook Mechanism:** Hooks `LaunchableImageView.onDraw` and `KeyguardBottomAreaView` lifecycle to inject Nothing OS's native `FrostedBackdropDrawable` directly beneath the shortcut icons, dynamically aligned to the wallpaper behind them.

## ✨ Features
- 🪟 **Authentic Frosted Glass Blur:** Leverages Nothing OS's native lockscreen backdrop engine for 100% parity with stock lockscreen widgets.
- ⚡ **Specular Highlight Edge:** Renders the signature Nothing OS 1px glass edge around each circular button.
- 🌓 **Dynamic Theme Sync:** Reacts instantaneously to Dark Mode / Light Mode and wallpaper shifts.
- 🚀 **120 FPS Native Performance:** Zero stutter or frame drops during lockscreen pull-down and unlock animations.

## 📱 Compatibility
- Nothing OS 5.0 (Android 17)
- Tested on Nothing Phone (3) (`A024`)

## 🛠️ Installation
1. Install `NothingBlurShortcuts.apk` on your device.
2. Open **LSPosed Manager** -> **Modules** -> Enable **NothingBlurShortcuts**.
3. Ensure the module scope has **System UI** (`com.android.systemui`) checked.
4. Restart SystemUI:
   ```bash
   adb shell su -c killall -9 com.android.systemui
   ```
   (or reboot your device).

## 🏗️ Building from Source
```bash
./build.sh
```
Output signed APK: `bin/NothingBlurShortcuts.apk`.

---

# Русский

## 💡 Концепция и архитектура

На экране блокировки Nothing OS:
- **Виджеты экрана блокировки** (часы, погода, плеер) выполнены в фирменном стиле «матового стекла» (Frosted Glass) с живым аппаратным размытием обоев и тонким контуром.
- **Нижние ярлыки** (две кнопки в нижних углах: камера, фонарик и т.д.) по умолчанию отображаются с плоским серым кругом без какого-либо размытия.

### Почему чистый модуль KernelSU / Overlay не подходит:
Через RRO (Runtime Resource Overlay) в KernelSU можно подменять только статичные ресурсы (цвета, векторные XML вроде `@drawable/keyguard_bottom_affordance_bg`). Динамическое размытие обоев невозможно реализовать через XML — для этого нужен доступ к системному движку обоев, расчёт экранных координат и отрисовка нативного шейдера в реальном времени.

### Как работает LSPosed-модуль:
- **Целевой процесс:** `com.android.systemui` (SystemUI).
- **Элементы кнопок:** в контейнере `KeyguardBottomAreaView` находятся две вьюшки `LaunchableImageView` с ID `@id/start_button` и `@id/end_button`.
- **Внедрение:** Модуль подключается к нативным классам `FrostedBackdropProvider` и `FrostedBackdropDrawable`, которые уже встроены в SystemUI Nothing OS, перехватывает отрисовку кнопок и рендерит под каждой из них настоящее матовое стекло, повторяющее стиль виджетов Nothing OS.

## 🏗️ Сборка
```bash
./build.sh
```
Готовый подписанный APK формируется по пути: `bin/NothingBlurShortcuts.apk`.
