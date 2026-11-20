package com.nothing.blurshortcuts;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "NothingBlurShortcuts";

    private static Class<?> drawableClass;
    private static Method setClipRadiusMethod;
    private static Method updateOffsetMethod;
    private static Method setAnimateEntranceMethod;
    private static Method completeTransitionNowMethod;
    private static Method onThemeChangedMethod;

    private static Class<?> providerClass;
    private static Object backdropProvider;

    // Cache drawables per shortcut slot
    private static Drawable leftDrawable;
    private static Drawable rightDrawable;
    private static volatile int lastUiMode = -1;

    // Status Bar & Shade State tracking
    private static volatile Object sStatusBarStateController = null;
    private static volatile Object sAmbientState = null;
    private static volatile int sStatusBarState = 1; // 1 = KEYGUARD, 0 = SHADE, 2 = SHADE_LOCKED
    private static volatile boolean sIsShadeExpanded = false;
    private static volatile float sLsToShadeProgress = 0.0f;
    private static volatile float sFractionToShade = 0.0f;
    private static volatile boolean sIsDozing = false;
    private static volatile float sDozeAmount = 0.0f;
    private static volatile boolean sInitialized = false;

    // Fast reusable draw objects (UI Thread only)
    private static final RectF sDrawRectF = new RectF();
    private static final Path sClipPath = new Path();
    private static final float[] sCornerRadii = new float[8];

    // Pre-reflected fast methods for notification rows
    private static Method sIsUserExpandedMethod = null;
    private static Method sIsOnKeyguardMethod = null;
    private static Method sIsPinnedMethod = null;
    private static Method sIsColorizedMethod = null;
    private static Method sGetTopCornerRadiusMethod = null;
    private static Method sGetBottomCornerRadiusMethod = null;

    private static final Paint glassTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glassBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        glassTintPaint.setStyle(Paint.Style.FILL);
        glassBorderPaint.setStyle(Paint.Style.STROKE);
        glassBorderPaint.setStrokeWidth(2.0f); // 2px crisp specular border
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        Log.e(TAG, "NothingBlurShortcuts handleLoadPackage in com.android.systemui");

        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Application app = (Application) param.thisObject;
                    if (app != null && "com.android.systemui".equals(app.getPackageName())) {
                        Log.e(TAG, "Application.onCreate called! Initializing with app ClassLoader.");
                        initSystemUI(app.getClassLoader());
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Application.onCreate: " + t.getMessage());
        }

        initSystemUI(lpparam.classLoader);
    }

    private static synchronized void initSystemUI(ClassLoader cl) {
        if (cl == null || sInitialized) return;
        try {
            cl.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
            sInitialized = true;
        } catch (Throwable ignored) {
            return;
        }

        // 1. Enable SystemUI Lockscreen Blur Feature Flags
        try {
            Class<?> flagsClass = cl.loadClass("com.android.systemui.Flags");

            XposedBridge.hookAllMethods(flagsClass, "enableLockscreenBlur", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(true);
                }
            });

            XposedBridge.hookAllMethods(flagsClass, "blurOnMoreSurfaces", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(true);
                }
            });

            Log.e(TAG, "Successfully enabled Flags.enableLockscreenBlur & blurOnMoreSurfaces!");
        } catch (Throwable t) {
            Log.e(TAG, "Flags hook check: " + t.getMessage());
        }

        // 2. Discover FrostedBackdropDrawable and FrostedBackdropProvider
        try {
            providerClass = cl.loadClass("com.nothing.cardhost.frosted.FrostedBackdropProvider");
            Object companion = XposedHelpers.getStaticObjectField(providerClass, "Companion");
            backdropProvider = XposedHelpers.callMethod(companion, "getInstance");
            Log.e(TAG, "FrostedBackdropProvider instance: " + backdropProvider);

            drawableClass = cl.loadClass("com.nothing.commBase.frosted.stdview.FrostedBackdropDrawable");
            for (Method m : drawableClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("setClipRadius")) setClipRadiusMethod = m;
                else if (name.equals("updateOffset")) updateOffsetMethod = m;
                else if (name.equals("setAnimateEntrance")) setAnimateEntranceMethod = m;
                else if (name.equals("completeTransitionNow")) completeTransitionNowMethod = m;
                else if (name.equals("onThemeChanged")) onThemeChangedMethod = m;
            }
            if (setClipRadiusMethod != null) setClipRadiusMethod.setAccessible(true);
            if (updateOffsetMethod != null) updateOffsetMethod.setAccessible(true);
            if (setAnimateEntranceMethod != null) setAnimateEntranceMethod.setAccessible(true);
            if (completeTransitionNowMethod != null) completeTransitionNowMethod.setAccessible(true);
            if (onThemeChangedMethod != null) onThemeChangedMethod.setAccessible(true);
            Log.e(TAG, "FrostedBackdropDrawable methods reflected successfully!");
        } catch (Throwable t) {
            Log.e(TAG, "Frosted classes check: " + t.getMessage());
        }

        // 3. Hook Jetpack Compose Lockscreen Shortcuts: ShortcutElementProvider
        try {
            Class<?> shortcutProviderClass = cl.loadClass("com.android.systemui.keyguard.ui.composable.elements.ShortcutElementProvider");
            ClassLoader composeCl = shortcutProviderClass.getClassLoader();

            // Strip solid background plate from BackgroundKt
            try {
                Class<?> bgKtClass = composeCl.loadClass("androidx.compose.foundation.BackgroundKt");
                XC_MethodHook stripBgHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        for (StackTraceElement ste : st) {
                            if (ste.getClassName().contains("ShortcutElementProvider")) {
                                param.setResult(param.args[0]);
                                return;
                            }
                        }
                    }
                };

                for (Method m : bgKtClass.getDeclaredMethods()) {
                    if (m.getName().startsWith("background")) {
                        try {
                            XposedBridge.hookMethod(m, stripBgHook);
                            Log.e(TAG, "Hooked BackgroundKt." + m.getName() + " to strip solid plate!");
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed hooking BackgroundKt." + m.getName() + ": " + t);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "BackgroundKt hook error: " + t);
            }

            // Hook shortcutBackground directly as a second line of defense
            for (Method m : shortcutProviderClass.getDeclaredMethods()) {
                if (m.getName().equals("shortcutBackground")) {
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(param.args[0]);
                            }
                        });
                        Log.e(TAG, "Successfully hooked shortcutBackground method!");
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed hooking shortcutBackground: " + t);
                    }
                }
            }

            // Force ONLY boolean isBlurCurrentlySupported state lambdas to return true
            for (Method m : shortcutProviderClass.getDeclaredMethods()) {
                if (m.getName().contains("lambda") && m.getReturnType() == boolean.class &&
                    (m.getName().contains("shortcutBackground") || m.getName().contains("drawBlurBehind"))) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        });
                        Log.e(TAG, "Hooked blur state boolean lambda: " + m.getName());
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed hooking lambda " + m.getName() + ": " + t.getMessage());
                    }
                }
            }

            // Hook drawBlurBehind$lambda$49$lambda$48 (Compose background draw callback)
            for (Method m : shortcutProviderClass.getDeclaredMethods()) {
                if (m.getName().contains("lambda$49$lambda$48")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (sIsDozing || sDozeAmount > 0.005f) {
                                    param.setResult(null);
                                    return;
                                }
                                try {
                                    Object drawScope = param.args[5];
                                    Object viewModel = param.args[3];
                                    
                                    // Extract native Canvas from Compose DrawScope
                                    Object drawContext = XposedHelpers.callMethod(drawScope, "getDrawContext");
                                    Object composeCanvas = XposedHelpers.callMethod(drawContext, "getCanvas");
                                    Canvas nativeCanvas = getNativeCanvas(composeCanvas);

                                    if (nativeCanvas == null) {
                                        param.setResult(null);
                                        return;
                                    }

                                    // Context & UI Theme Mode (Light vs Dark) detection
                                    Context ctx = null;
                                    try {
                                        ctx = (Context) XposedHelpers.callStaticMethod(
                                            XposedHelpers.findClass("android.app.ActivityThread", null),
                                            "currentApplication"
                                        );
                                    } catch (Throwable ignored) {}

                                    int currentUiMode = Configuration.UI_MODE_NIGHT_NO;
                                    if (ctx != null) {
                                        currentUiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                                    }
                                    boolean isDark = (currentUiMode == Configuration.UI_MODE_NIGHT_YES);

                                    // Respond to theme change in real-time
                                    if (lastUiMode != -1 && lastUiMode != currentUiMode) {
                                        Log.e(TAG, "Theme mode changed to " + (isDark ? "DARK" : "LIGHT") + "! Updating drawables.");
                                        if (onThemeChangedMethod != null) {
                                            try {
                                                if (leftDrawable != null) onThemeChangedMethod.invoke(leftDrawable);
                                                if (rightDrawable != null) onThemeChangedMethod.invoke(rightDrawable);
                                            } catch (Throwable ignored) {}
                                        }
                                        leftDrawable = null;
                                        rightDrawable = null;
                                    }
                                    lastUiMode = currentUiMode;

                                    long sizeBits = (Long) XposedHelpers.callMethod(drawScope, "getSize-NH-jbRc");
                                    float width = Float.intBitsToFloat((int) (sizeBits >> 32));
                                    float height = Float.intBitsToFloat((int) (sizeBits & 0xFFFFFFFFL));
                                    float radius = Math.min(width, height) / 2.0f;
                                    float cx = width / 2.0f;
                                    float cy = height / 2.0f;

                                    Matrix matrix = new Matrix();
                                    nativeCanvas.getMatrix(matrix);
                                    float[] matrixValues = new float[9];
                                    matrix.getValues(matrixValues);
                                    float screenX = matrixValues[Matrix.MTRANS_X];
                                    float screenY = matrixValues[Matrix.MTRANS_Y];

                                    Rect clip = nativeCanvas.getClipBounds();
                                    if (screenX == 0f && clip != null && clip.left > 0) {
                                        screenX = clip.left;
                                        screenY = clip.top;
                                    }

                                    boolean isLeftSlot = true;
                                    String vmStr = String.valueOf(viewModel);
                                    if (screenX > 500f || vmStr.contains("bottom_end") || vmStr.contains("end") || vmStr.contains("camera") || vmStr.contains("qr")) {
                                        isLeftSlot = false;
                                    }

                                    if (screenX <= 0f) {
                                        screenX = isLeftSlot ? 100f : 980f;
                                        screenY = 2550f;
                                    }

                                    // Refresh provider if needed
                                    if (backdropProvider == null && providerClass != null) {
                                        try {
                                            Object companion = XposedHelpers.getStaticObjectField(providerClass, "Companion");
                                            backdropProvider = XposedHelpers.callMethod(companion, "getInstance");
                                        } catch (Throwable ignored) {}
                                    }

                                    // Get or create FrostedBackdropDrawable per slot
                                    Drawable frosted = isLeftSlot ? leftDrawable : rightDrawable;
                                    if (frosted == null && backdropProvider != null && drawableClass != null && ctx != null) {
                                        try {
                                            frosted = (Drawable) XposedHelpers.newInstance(drawableClass, ctx, backdropProvider);
                                            if (frosted != null) {
                                                frosted.setCallback(null);
                                                if (setAnimateEntranceMethod != null) {
                                                    setAnimateEntranceMethod.invoke(frosted, false);
                                                }
                                                if (completeTransitionNowMethod != null) {
                                                    completeTransitionNowMethod.invoke(frosted);
                                                }
                                                if (onThemeChangedMethod != null) {
                                                    onThemeChangedMethod.invoke(frosted);
                                                }
                                                if (isLeftSlot) {
                                                    leftDrawable = frosted;
                                                } else {
                                                    rightDrawable = frosted;
                                                }
                                                Log.e(TAG, "Created FrostedBackdropDrawable for slot: " + (isLeftSlot ? "LEFT" : "RIGHT") + " (isDark=" + isDark + ")");
                                            }
                                        } catch (Throwable t) {
                                            Log.e(TAG, "Failed creating FrostedBackdropDrawable: " + t);
                                        }
                                    }

                                    // 1. Render authentic blurred wallpaper backdrop
                                    if (frosted != null) {
                                        if (setClipRadiusMethod != null) {
                                            setClipRadiusMethod.invoke(frosted, radius);
                                        }
                                        if (updateOffsetMethod != null) {
                                            updateOffsetMethod.invoke(frosted, screenX, screenY);
                                        }
                                        frosted.setBounds(0, 0, (int) width, (int) height);
                                        frosted.draw(nativeCanvas);
                                    }

                                    // 2. Render Widget-style frosted glass plate overlay dynamically adapted to Dark/Light theme
                                    if (isDark) {
                                        // Dark theme: translucent smoked glass overlay + subtle specular edge rim
                                        glassTintPaint.setColor(0x44000000);
                                        glassBorderPaint.setColor(0x28FFFFFF);
                                    } else {
                                        // Light theme: translucent milky white glass overlay + specular edge rim
                                        glassTintPaint.setColor(0x38FFFFFF);
                                        glassBorderPaint.setColor(0x45FFFFFF);
                                    }

                                    nativeCanvas.drawCircle(cx, cy, radius, glassTintPaint);
                                    nativeCanvas.drawCircle(cx, cy, radius - 1.0f, glassBorderPaint);

                                } catch (Throwable t) {
                                    Log.e(TAG, "Error in drawBlurBehind lambda: " + t, t);
                                } finally {
                                    // CANCEL original method so BackgroundBlurDrawable does not draw opaque plate
                                    param.setResult(null);
                                }
                            }
                        });
                        Log.e(TAG, "Successfully hooked drawBehind draw lambda: " + m.getName());
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed hooking drawBehind draw lambda: " + t);
                    }
                }
            }

            Log.e(TAG, "Successfully hooked Compose ShortcutElementProvider!");
        } catch (Throwable t) {
            Log.e(TAG, "ShortcutElementProvider hook check: " + t.getMessage());
        }

        // 4. Hook Volume Dialog (Android 15 / Nothing OS 5.0 volume architecture)
        try {
            Class<?> volDialogClass = cl.loadClass("com.android.systemui.volume.dialog.VolumeDialog");
            Log.e(TAG, "Found VolumeDialog class: " + volDialogClass);

            XposedBridge.hookAllConstructors(volDialogClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Log.e(TAG, "VolumeDialog constructor called!");
                }
            });

            XposedBridge.hookAllMethods(volDialogClass, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    android.app.Dialog dialog = (android.app.Dialog) param.thisObject;
                    if (dialog != null && dialog.getWindow() != null) {
                        View decor = dialog.getWindow().getDecorView();
                        styleVolumeDialog(decor);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "VolumeDialog class hook check: " + t.getMessage());
        }

        // Hook VolumeDialogViewBinder to apply authentic Frosted Glass styling
        try {
            Class<?> binderCls = cl.loadClass("com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder");
            for (Method m : binderCls.getDeclaredMethods()) {
                m.setAccessible(true);
                if (m.getName().equals("applyAnimationProgress") || m.getName().equals("animateVisibility") || m.getName().equals("bind")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            for (Object arg : param.args) {
                                if (arg instanceof View) {
                                    styleVolumeDialog((View) arg);
                                } else if (arg instanceof android.app.Dialog) {
                                    android.app.Dialog d = (android.app.Dialog) arg;
                                    if (d.getWindow() != null) {
                                        styleVolumeDialog(d.getWindow().getDecorView());
                                    }
                                }
                            }
                        }
                    });
                }
            }
            Log.e(TAG, "VolumeDialogViewBinder hooked for frosted glass styling!");
        } catch (Throwable t) {
            Log.e(TAG, "VolumeDialogViewBinder hook check: " + t.getMessage());
        }

        // Hook VolumeDialogRingerViewBinder to style ringer drawer
        try {
            Class<?> ringerBinderCls = cl.loadClass("com.android.systemui.volume.dialog.ringer.ui.binder.VolumeDialogRingerViewBinder");
            for (Method m : ringerBinderCls.getDeclaredMethods()) {
                m.setAccessible(true);
                if (m.getName().equals("updateBackground") || m.getName().equals("bind") || m.getName().equals("setIsBlurSupported")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            for (Object arg : param.args) {
                                if (arg instanceof View) {
                                    styleVolumeDialog((View) arg);
                                }
                            }
                        }
                    });
                }
            }
            Log.e(TAG, "VolumeDialogRingerViewBinder hooked for frosted glass styling!");
        } catch (Throwable t) {
            Log.e(TAG, "VolumeDialogRingerViewBinder hook check: " + t.getMessage());
        }

        // Hook VolumeDialogSettingsButtonViewBinder
        try {
            Class<?> settingsBinderCls = cl.loadClass("com.android.systemui.volume.dialog.settings.ui.binder.VolumeDialogSettingsButtonViewBinder");
            for (Method m : settingsBinderCls.getDeclaredMethods()) {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        for (Object arg : param.args) {
                            if (arg instanceof View) {
                                styleVolumeDialog((View) arg);
                            }
                        }
                    }
                });
            }
            Log.e(TAG, "VolumeDialogSettingsButtonViewBinder hooked for frosted glass styling!");
        } catch (Throwable t) {
            Log.e(TAG, "VolumeDialogSettingsButtonViewBinder hook check: " + t.getMessage());
        }

        // 5. Hook Notifications for Lockscreen & Heads-Up Frosted Blur Style
        try {
            // Track Status Bar State, Shade Expansion & Dozing via StatusBarStateControllerImpl
            try {
                Class<?> sscCls = cl.loadClass("com.android.systemui.statusbar.StatusBarStateControllerImpl");
                XposedBridge.hookAllConstructors(sscCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sStatusBarStateController = param.thisObject;
                        try {
                            sStatusBarState = (Integer) XposedHelpers.getObjectField(param.thisObject, "mState");
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.hookAllMethods(sscCls, "setState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            sStatusBarState = (Integer) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(sscCls, "onStatusBarStateChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            sStatusBarState = (Integer) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(sscCls, "onShadeOrQsExpanded", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                            sIsShadeExpanded = (Boolean) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(sscCls, "setIsDozing", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                            sIsDozing = (Boolean) param.args[0];
                        }
                    }
                });
                XC_MethodHook dozeAmountHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            sDozeAmount = (Float) param.args[0];
                        }
                    }
                };
                try { XposedBridge.hookAllMethods(sscCls, "setDozeAmountInternal", dozeAmountHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(sscCls, "setAndInstrumentDozeAmount", dozeAmountHook); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Track state via AmbientState
            try {
                Class<?> asCls = cl.loadClass("com.android.systemui.statusbar.notification.stack.AmbientState");
                XposedBridge.hookAllConstructors(asCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setStatusBarState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            sStatusBarState = (Integer) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setShadeExpanded", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                            sIsShadeExpanded = (Boolean) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setLStoShadeProgress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            sLsToShadeProgress = (Float) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setFractionToShade", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            sFractionToShade = (Float) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setDozing", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                            sIsDozing = (Boolean) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setDozeAmount", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            sDozeAmount = (Float) param.args[0];
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Track state via NotificationStackScrollLayout
            try {
                Class<?> nsslCls = cl.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
                XposedBridge.hookAllMethods(nsslCls, "setStatusBarState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            sStatusBarState = (Integer) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setLStoShadeProgress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            sLsToShadeProgress = (Float) param.args[0];
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setFractionToShade", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            sFractionToShade = (Float) param.args[0];
                        }
                    }
                });
            } catch (Throwable ignored) {}

            Class<?> notifBgClass = cl.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
            Class<?> notifRowClass = cl.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
            Class<?> notifShelfClass = null;
            try {
                notifShelfClass = cl.loadClass("com.android.systemui.statusbar.NotificationShelf");
            } catch (Throwable ignored) {}

            final Class<?> shelfCls = notifShelfClass;

            if (shelfCls != null) {
                try {
                    XposedBridge.hookAllMethods(shelfCls, "setStatusBarState", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                sStatusBarState = (Integer) param.args[0];
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // Pre-reflect methods on ExpandableNotificationRow for maximum 120 FPS performance
            try { sIsUserExpandedMethod = notifRowClass.getMethod("isUserExpanded"); sIsUserExpandedMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsOnKeyguardMethod = notifRowClass.getMethod("isOnKeyguard"); sIsOnKeyguardMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsPinnedMethod = notifRowClass.getMethod("isPinned"); sIsPinnedMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsColorizedMethod = notifRowClass.getMethod("isColorized"); sIsColorizedMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetTopCornerRadiusMethod = notifRowClass.getMethod("getTopCornerRadius"); sGetTopCornerRadiusMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetBottomCornerRadiusMethod = notifRowClass.getMethod("getBottomCornerRadius"); sGetBottomCornerRadiusMethod.setAccessible(true); } catch (Throwable ignored) {}

            XC_MethodHook notifDrawHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View bgView = (View) param.thisObject;

                    // 1. Instant AOD check (pure primitive read)
                    if (sIsDozing || sDozeAmount > 0.005f) {
                        cleanupBlur(bgView);
                        return;
                    }

                    int state = sStatusBarState;
                    if (sStatusBarStateController != null) {
                        try {
                            state = (Integer) XposedHelpers.getObjectField(sStatusBarStateController, "mState");
                            sStatusBarState = state;
                        } catch (Throwable ignored) {}
                    }

                    View parent = (View) bgView.getParent();

                    // 2. One-time caching of sStatusBarStateController & sAmbientState if not yet set
                    if (sStatusBarStateController == null && parent != null) {
                        try {
                            Object ssc = XposedHelpers.getObjectField(parent, "mStatusBarStateController");
                            if (ssc != null) sStatusBarStateController = ssc;
                        } catch (Throwable ignored) {}
                    }
                    if (sAmbientState == null && parent != null) {
                        View v = parent;
                        while (v != null) {
                            try {
                                Object as = XposedHelpers.getObjectField(v, "mAmbientState");
                                if (as != null) { sAmbientState = as; break; }
                            } catch (Throwable ignored) {}
                            ViewParent vp = v.getParent();
                            v = (vp instanceof View) ? (View) vp : null;
                        }
                    }

                    float lsToShade = sLsToShadeProgress;
                    if (sAmbientState != null) {
                        try {
                            lsToShade = (Float) XposedHelpers.getObjectField(sAmbientState, "mLStoShadeProgress");
                            sLsToShadeProgress = lsToShade;
                        } catch (Throwable ignored) {}
                    }

                    // 3. Fast direct method invocation on parent row
                    boolean isPinned = false;
                    boolean isColorized = false;
                    boolean isShelf = false;
                    boolean onKeyguard = false;
                    boolean rowOnKeyguard = false;
                    boolean isUserExpanded = false;

                    try { onKeyguard = (Boolean) XposedHelpers.getObjectField(bgView, "mOnKeyguard"); } catch (Throwable ignored) {}

                    if (parent != null && notifRowClass.isAssignableFrom(parent.getClass())) {
                        if (sIsOnKeyguardMethod != null) {
                            try { rowOnKeyguard = (Boolean) sIsOnKeyguardMethod.invoke(parent); } catch (Throwable ignored) {}
                        } else {
                            try { rowOnKeyguard = (Boolean) XposedHelpers.callMethod(parent, "isOnKeyguard"); } catch (Throwable ignored) {}
                        }
                        if (sIsPinnedMethod != null) {
                            try { isPinned = (Boolean) sIsPinnedMethod.invoke(parent); } catch (Throwable ignored) {}
                        } else {
                            try { isPinned = (Boolean) XposedHelpers.callMethod(parent, "isPinned"); } catch (Throwable ignored) {}
                        }
                        if (sIsColorizedMethod != null) {
                            try { isColorized = (Boolean) sIsColorizedMethod.invoke(parent); } catch (Throwable ignored) {}
                        } else {
                            try { isColorized = (Boolean) XposedHelpers.callMethod(parent, "isColorized"); } catch (Throwable ignored) {}
                        }
                        if (sIsUserExpandedMethod != null) {
                            try { isUserExpanded = (Boolean) sIsUserExpandedMethod.invoke(parent); } catch (Throwable ignored) {}
                        } else {
                            try { isUserExpanded = (Boolean) XposedHelpers.callMethod(parent, "isUserExpanded"); } catch (Throwable ignored) {}
                        }
                    } else if (parent != null && shelfCls != null && shelfCls.isAssignableFrom(parent.getClass())) {
                        isShelf = true;
                    }

                    // 4. Target determination:
                    // State: 1 = KEYGUARD, 0 = SHADE, 2 = SHADE_LOCKED
                    // When shade menu is opened over lockscreen, state transitions to 2 or lsToShade > 0.05f or sIsShadeExpanded is true.
                    boolean isShadeOrMenu = (state != 1) || sIsShadeExpanded || (lsToShade > 0.05f) || (sFractionToShade > 0.05f);

                    // Lockscreen notifications (cards & shelf) ONLY when resting cleanly on lockscreen (state == 1)
                    boolean isLockscreenTarget = (state == 1)
                            && (onKeyguard || rowOnKeyguard || isShelf)
                            && !isShadeOrMenu
                            && !isUserExpanded;

                    // Heads-up incoming pop-up notifications over active apps (state == 0 and actively pinned banner)
                    boolean isHeadsUpTarget = (state == 0) && isPinned && !sIsShadeExpanded;

                    boolean isTarget = (isLockscreenTarget || isHeadsUpTarget) && !isColorized;
                    if (!isTarget) {
                        cleanupBlur(bgView);
                        return;
                    }

                    Canvas canvas = (Canvas) param.args[0];
                    if (canvas == null) return;

                    Context ctx = bgView.getContext();
                    if (ctx == null) return;

                    int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);

                    int bgTop = 0;
                    try { bgTop = (Integer) XposedHelpers.getObjectField(bgView, "mBackgroundTop"); } catch (Throwable ignored) {}
                    int actualWidth = 0;
                    try { actualWidth = (Integer) XposedHelpers.getObjectField(bgView, "mActualWidth"); } catch (Throwable ignored) {}
                    int actualHeight = 0;
                    try { actualHeight = (Integer) XposedHelpers.getObjectField(bgView, "mActualHeight"); } catch (Throwable ignored) {}

                    if (actualWidth <= 0) actualWidth = bgView.getWidth();
                    if (actualHeight <= 0) {
                        if (parent != null) {
                            try { actualHeight = (Integer) XposedHelpers.callMethod(parent, "getActualHeight"); } catch (Throwable ignored) {}
                        }
                        if (actualHeight <= 0) actualHeight = bgView.getHeight();
                    }

                    int clipTop = 0;
                    try { clipTop = (Integer) XposedHelpers.getObjectField(bgView, "mClipTopAmount"); } catch (Throwable ignored) {}
                    int clipBottom = 0;
                    try { clipBottom = (Integer) XposedHelpers.getObjectField(bgView, "mClipBottomAmount"); } catch (Throwable ignored) {}

                    int drawLeft = 0;
                    int drawRight = actualWidth;
                    int drawTop = Math.max(bgTop, clipTop);
                    int drawBottom = clipBottom > 0 ? (actualHeight - clipBottom) : actualHeight;

                    if (drawRight <= drawLeft || drawBottom <= drawTop) {
                        return;
                    }

                    // System corner radius detection via Roundable interface:
                    float densityF = ctx.getResources().getDisplayMetrics().density;
                    float defaultRadius = 18.0f * densityF;
                    float topRadius = defaultRadius;
                    float bottomRadius = defaultRadius;

                    if (isShelf) {
                        topRadius = (drawBottom - drawTop) / 2.0f;
                        bottomRadius = topRadius;
                    } else if (parent != null) {
                        if (sGetTopCornerRadiusMethod != null && sGetBottomCornerRadiusMethod != null) {
                            try {
                                Float tr = (Float) sGetTopCornerRadiusMethod.invoke(parent);
                                Float br = (Float) sGetBottomCornerRadiusMethod.invoke(parent);
                                if (tr != null && br != null && (tr > 0f || br > 0f)) {
                                    topRadius = tr;
                                    bottomRadius = br;
                                }
                            } catch (Throwable ignored) {}
                        } else {
                            try {
                                Float tr = (Float) XposedHelpers.callMethod(parent, "getTopCornerRadius");
                                Float br = (Float) XposedHelpers.callMethod(parent, "getBottomCornerRadius");
                                if (tr != null && br != null && (tr > 0f || br > 0f)) {
                                    topRadius = tr;
                                    bottomRadius = br;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }

                    // Reusable fast draw path (zero GC allocations)
                    sDrawRectF.set(drawLeft, drawTop, drawRight, drawBottom);
                    sCornerRadii[0] = topRadius;
                    sCornerRadii[1] = topRadius;
                    sCornerRadii[2] = topRadius;
                    sCornerRadii[3] = topRadius;
                    sCornerRadii[4] = bottomRadius;
                    sCornerRadii[5] = bottomRadius;
                    sCornerRadii[6] = bottomRadius;
                    sCornerRadii[7] = bottomRadius;

                    sClipPath.reset();
                    sClipPath.addRoundRect(sDrawRectF, sCornerRadii, Path.Direction.CW);

                    canvas.save();
                    canvas.clipPath(sClipPath);

                    // 1. Hardware cross-window GPU blur
                    Object blurDrawable = null;
                    try {
                        blurDrawable = XposedHelpers.getObjectField(bgView, "mBackgroundBlurDrawable");
                    } catch (Throwable ignored) {}

                    if (blurDrawable == null) {
                        try {
                            Object vri = XposedHelpers.callMethod(bgView, "getViewRootImpl");
                            if (vri != null) {
                                blurDrawable = XposedHelpers.callMethod(vri, "createBackgroundBlurDrawable");
                                XposedHelpers.setObjectField(bgView, "mBackgroundBlurDrawable", blurDrawable);
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (blurDrawable != null) {
                        try {
                            XposedHelpers.callMethod(blurDrawable, "setBlurRadius", 160);
                        } catch (Throwable ignored) {}
                        try {
                            XposedHelpers.callMethod(blurDrawable, "setCornerRadius", topRadius, topRadius, bottomRadius, bottomRadius);
                        } catch (Throwable t) {
                            try {
                                XposedHelpers.callMethod(blurDrawable, "setCornerRadius", Math.max(topRadius, bottomRadius));
                            } catch (Throwable ignored) {}
                        }
                        Drawable bd = (Drawable) blurDrawable;
                        bd.setBounds(drawLeft, drawTop, drawRight, drawBottom);
                        bd.draw(canvas);
                    }

                    // 2. Frosted Glass Tint & Specular Rim
                    if (isDark) {
                        glassTintPaint.setColor(0x44000000);
                        glassBorderPaint.setColor(0x28FFFFFF);
                    } else {
                        glassTintPaint.setColor(0x38FFFFFF);
                        glassBorderPaint.setColor(0x45FFFFFF);
                    }

                    canvas.drawPath(sClipPath, glassTintPaint);
                    canvas.drawPath(sClipPath, glassBorderPaint);

                    // 3. Ripple effect for touch feedback on top of frosted glass
                    try {
                        Object bgObj = XposedHelpers.getObjectField(bgView, "mBackground");
                        if (bgObj instanceof android.graphics.drawable.LayerDrawable) {
                            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bgObj;
                            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                                Drawable layer = ld.getDrawable(i);
                                if (layer instanceof android.graphics.drawable.RippleDrawable) {
                                    layer.setBounds(drawLeft, drawTop, drawRight, drawBottom);
                                    layer.draw(canvas);
                                }
                            }
                        } else if (bgObj instanceof android.graphics.drawable.RippleDrawable) {
                            Drawable rd = (Drawable) bgObj;
                            rd.setBounds(drawLeft, drawTop, drawRight, drawBottom);
                            rd.draw(canvas);
                        }
                    } catch (Throwable ignored) {}

                    canvas.restore();

                    // Cancel original onDraw so solid plate is not drawn
                    param.setResult(null);
                }
            };

            XposedBridge.hookAllMethods(notifBgClass, "onDraw", notifDrawHook);
            try {
                Class<?> notifShelfBgClass = cl.loadClass("com.android.systemui.statusbar.notification.shelf.NotificationShelfBackgroundView");
                if (notifShelfBgClass != null && notifShelfBgClass != notifBgClass) {
                    XposedBridge.hookAllMethods(notifShelfBgClass, "onDraw", notifDrawHook);
                }
            } catch (Throwable ignored) {}
            Log.e(TAG, "NotificationBackgroundView hooked for frosted glass blur style!");
        } catch (Throwable t) {
            Log.e(TAG, "Notification hooks check: " + t.getMessage());
        }
    }

    private static void styleVolumeDialog(View root) {
        if (root == null) return;
        Context ctx = root.getContext();
        if (ctx == null || ctx.getResources() == null) return;

        int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);

        int tintColor = isDark ? 0x44000000 : 0x38FFFFFF;
        int strokeColor = isDark ? 0x28FFFFFF : 0x45FFFFFF;
        int strokeWidth = Math.max(2, Math.round(1.0f * ctx.getResources().getDisplayMetrics().density));

        String pkg = ctx.getPackageName();

        // 1. Remove solid opaque background plate from volume_expand_bar_container
        int expandContainerId = root.getResources().getIdentifier("volume_expand_bar_container", "id", pkg);
        if (expandContainerId != 0) {
            View expandContainer = root.findViewById(expandContainerId);
            if (expandContainer != null) {
                if (expandContainer.getBackground() != null) {
                    Log.e(TAG, "styleVolumeDialog: clearing background on volume_expand_bar_container (was " + expandContainer.getBackground().getClass().getSimpleName() + ")");
                    expandContainer.setBackground(null);
                }
            }
        }

        // 2. Style volume_dialog_background (the main volume capsule)
        int bgId = root.getResources().getIdentifier("volume_dialog_background", "id", pkg);
        if (bgId != 0) {
            View bgView = root.findViewById(bgId);
            if (bgView != null) {
                Log.e(TAG, "styleVolumeDialog: applying frosted glass to volume_dialog_background (isDark=" + isDark + ")");
                applyFrostedGlassToDrawable(bgView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        // 3. Style ringer_buttons_background (the ringer drawer capsule)
        int ringerBgId = root.getResources().getIdentifier("ringer_buttons_background", "id", pkg);
        if (ringerBgId != 0) {
            View ringerBg = root.findViewById(ringerBgId);
            if (ringerBg != null) {
                applyFrostedGlassToDrawable(ringerBg.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        // 4. Style odi_captions if present
        int captionsId = root.getResources().getIdentifier("odi_captions", "id", pkg);
        if (captionsId != 0) {
            View captionsView = root.findViewById(captionsId);
            if (captionsView != null) {
                applyFrostedGlassToDrawable(captionsView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        // 5. Style accessibility & assistant sliders if present
        int a11yId = root.getResources().getIdentifier("accessibility_slider_container", "id", pkg);
        if (a11yId != 0) {
            View a11yView = root.findViewById(a11yId);
            if (a11yView != null) {
                applyFrostedGlassToDrawable(a11yView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        int assistantId = root.getResources().getIdentifier("assistant_slider_container", "id", pkg);
        if (assistantId != 0) {
            View assistantView = root.findViewById(assistantId);
            if (assistantView != null) {
                applyFrostedGlassToDrawable(assistantView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }
    }

    private static void applyFrostedGlassToDrawable(Drawable bg, int tintColor, int strokeWidth, int strokeColor) {
        if (bg == null) return;
        android.graphics.drawable.GradientDrawable gd = null;
        if (bg instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bg;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                Drawable d = ld.getDrawable(i);
                if (d instanceof android.graphics.drawable.GradientDrawable) {
                    gd = (android.graphics.drawable.GradientDrawable) d;
                    break;
                }
            }
        } else if (bg instanceof android.graphics.drawable.GradientDrawable) {
            gd = (android.graphics.drawable.GradientDrawable) bg;
        }

        if (gd != null) {
            gd.setColor(tintColor);
            gd.setStroke(strokeWidth, strokeColor);
        }
    }

    private static void cleanupBlur(View bgView) {
        if (bgView == null) return;
        try {
            Object bd = XposedHelpers.getObjectField(bgView, "mBackgroundBlurDrawable");
            if (bd != null) {
                XposedHelpers.callMethod(bd, "setBlurRadius", 0);
                XposedHelpers.setObjectField(bgView, "mBackgroundBlurDrawable", null);
            }
        } catch (Throwable ignored) {}
    }

    private static Canvas getNativeCanvas(Object composeCanvas) {
        if (composeCanvas == null) return null;
        try {
            Method m = composeCanvas.getClass().getMethod("getInternalCanvas");
            return (Canvas) m.invoke(composeCanvas);
        } catch (Throwable ignored) {}
        try {
            Field f = composeCanvas.getClass().getDeclaredField("_internalCanvas");
            f.setAccessible(true);
            return (Canvas) f.get(composeCanvas);
        } catch (Throwable ignored) {}
        try {
            Field f = composeCanvas.getClass().getDeclaredField("internalCanvas");
            f.setAccessible(true);
            return (Canvas) f.get(composeCanvas);
        } catch (Throwable ignored) {}
        for (Field f : composeCanvas.getClass().getDeclaredFields()) {
            if (Canvas.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Canvas c = (Canvas) f.get(composeCanvas);
                    if (c != null) return c;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
