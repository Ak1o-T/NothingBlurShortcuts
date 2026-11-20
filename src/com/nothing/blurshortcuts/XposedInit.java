package com.nothing.blurshortcuts;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

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
        if (cl == null) return;

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
                                Log.e(TAG, "STRIPPED SOLID PLATE in BackgroundKt! Returning unbacked modifier.");
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
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "shortcutBackground BEFORE: incoming modifier = " + param.args[0]);
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "shortcutBackground AFTER: replacing with transparent modifier = " + param.args[0]);
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
