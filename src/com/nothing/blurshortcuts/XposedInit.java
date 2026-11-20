package com.nothing.blurshortcuts;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "NothingBlurShortcuts";

    private static Class<?> providerClass;
    private static Object providerInstance;

    private static Class<?> drawableClass;
    private static Method setClipRadiusMethod;
    private static Method updateOffsetMethod;
    private static Method setAnimateEntranceMethod;
    private static Method completeTransitionNowMethod;
    private static Method onThemeChangedMethod;

    private static final Set<View> shortcutButtons = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, FrostedShortcutBackdrop> backdropMap = new WeakHashMap<>();

    private static final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glassTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF tempRectF = new RectF();

    static {
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
        glassTintPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        Log.i(TAG, "NothingBlurShortcuts initialized in com.android.systemui");

        // 1. Resolve Nothing OS Frosted Backdrop classes
        try {
            providerClass = XposedHelpers.findClass("com.nothing.cardhost.frosted.FrostedBackdropProvider", lpparam.classLoader);
        } catch (Throwable t) {
            Log.w(TAG, "FrostedBackdropProvider not found in primary path, trying secondary...");
            try {
                providerClass = XposedHelpers.findClass("com.nothing.systemui.widget.NTKeyguardFrostedBackdrop", lpparam.classLoader);
            } catch (Throwable t2) {
                Log.e(TAG, "Could not resolve frosted backdrop provider: " + t2.getMessage());
            }
        }

        try {
            drawableClass = XposedHelpers.findClass("com.nothing.commBase.frosted.stdview.FrostedBackdropDrawable", lpparam.classLoader);
            setClipRadiusMethod = drawableClass.getMethod("setClipRadius", float.class);
            updateOffsetMethod = drawableClass.getMethod("updateOffset", float.class, float.class);
            completeTransitionNowMethod = drawableClass.getMethod("completeTransitionNow");
            onThemeChangedMethod = drawableClass.getMethod("onThemeChanged", int.class);
            try {
                setAnimateEntranceMethod = drawableClass.getMethod("setAnimateEntrance", boolean.class);
            } catch (Throwable ignored) {}
            Log.i(TAG, "Successfully resolved FrostedBackdropDrawable methods!");
        } catch (Throwable t) {
            Log.e(TAG, "Could not hook FrostedBackdropDrawable: " + t.getMessage());
        }

        // 2. Hook KeyguardBottomAreaView to locate and style affordance buttons
        try {
            Class<?> bottomAreaClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.KeyguardBottomAreaView", lpparam.classLoader);

            XposedBridge.hookAllMethods(bottomAreaClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    setupShortcuts((View) param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(bottomAreaClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    setupShortcuts((View) param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(bottomAreaClass, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    setupShortcuts((View) param.thisObject);
                }
            });
            Log.i(TAG, "Successfully hooked KeyguardBottomAreaView lifecycle!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook KeyguardBottomAreaView: " + t.getMessage(), t);
        }

        // 3. Hook KeyguardQuickAffordanceViewBinder for reactive binding updates
        try {
            Class<?> binderClass = XposedHelpers.findClass("com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder", lpparam.classLoader);
            XposedBridge.hookAllMethods(binderClass, "bind", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                        registerShortcutButton((View) param.args[0]);
                    }
                }
            });
            Log.i(TAG, "Successfully hooked KeyguardQuickAffordanceViewBinder!");
        } catch (Throwable t) {
            Log.w(TAG, "KeyguardQuickAffordanceViewBinder hook optional warning: " + t.getMessage());
        }

        // 4. Hook LaunchableImageView to draw frosted glass backdrop directly behind the shortcut icon
        try {
            Class<?> launchableImageClass = XposedHelpers.findClass("com.android.systemui.animation.view.LaunchableImageView", lpparam.classLoader);

            XposedBridge.hookAllMethods(launchableImageClass, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    if (!isShortcutButton(view)) {
                        return;
                    }

                    Canvas canvas = (Canvas) param.args[0];
                    if (canvas == null) {
                        return;
                    }

                    renderFrostedBackdrop(view, canvas);
                }
            });
            Log.i(TAG, "Successfully hooked LaunchableImageView.onDraw for shortcuts!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook LaunchableImageView: " + t.getMessage(), t);
        }
    }

    private static void setupShortcuts(View bottomArea) {
        if (bottomArea == null) return;
        Context ctx = bottomArea.getContext();
        int startId = ctx.getResources().getIdentifier("start_button", "id", "com.android.systemui");
        int endId = ctx.getResources().getIdentifier("end_button", "id", "com.android.systemui");

        if (startId != 0) {
            View startBtn = bottomArea.findViewById(startId);
            if (startBtn != null) {
                registerShortcutButton(startBtn);
            }
        }
        if (endId != 0) {
            View endBtn = bottomArea.findViewById(endId);
            if (endBtn != null) {
                registerShortcutButton(endBtn);
            }
        }
    }

    private static void registerShortcutButton(View button) {
        if (button == null) return;
        shortcutButtons.add(button);

        // Hide stock flat background drawable so it doesn't occlude our frosted glass
        Drawable bg = button.getBackground();
        if (bg != null && !(bg instanceof RippleDrawable)) {
            // Replace with transparent or ripple-only background
            button.setBackground(null);
        }
    }

    private static boolean isShortcutButton(View view) {
        if (shortcutButtons.contains(view)) {
            return true;
        }
        Context ctx = view.getContext();
        int id = view.getId();
        if (id != View.NO_ID && id != 0) {
            int startId = ctx.getResources().getIdentifier("start_button", "id", "com.android.systemui");
            int endId = ctx.getResources().getIdentifier("end_button", "id", "com.android.systemui");
            if (id == startId || id == endId) {
                shortcutButtons.add(view);
                return true;
            }
        }
        return false;
    }

    private static void renderFrostedBackdrop(View view, Canvas canvas) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        Context context = view.getContext();
        Object provider = getFrostedProvider(context, view.getClass().getClassLoader());
        if (provider == null || drawableClass == null) {
            return;
        }

        FrostedShortcutBackdrop backdrop = backdropMap.get(view);
        if (backdrop == null) {
            try {
                Drawable frostedDrawable = (Drawable) XposedHelpers.newInstance(drawableClass, context, provider);
                if (frostedDrawable != null) {
                    frostedDrawable.setCallback(null);
                    if (setAnimateEntranceMethod != null) {
                        setAnimateEntranceMethod.invoke(frostedDrawable, false);
                    }
                    if (completeTransitionNowMethod != null) {
                        completeTransitionNowMethod.invoke(frostedDrawable);
                    }
                    backdrop = new FrostedShortcutBackdrop(frostedDrawable);
                    backdropMap.put(view, backdrop);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed creating FrostedBackdropDrawable: " + t.getMessage());
                return;
            }
        }

        if (backdrop == null || backdrop.drawable == null) {
            return;
        }

        // Determine live screen coordinates for pixel-perfect wallpaper blur alignment
        view.getLocationOnScreen(backdrop.screenLoc);
        float targetX = (float) backdrop.screenLoc[0];
        float targetY = (float) backdrop.screenLoc[1];
        float radius = Math.min(w, h) / 2.0f;

        // Theme and configuration check
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);

        try {
            if (onThemeChangedMethod != null && backdrop.lastUiMode != uiMode) {
                onThemeChangedMethod.invoke(backdrop.drawable, uiMode);
                backdrop.lastUiMode = uiMode;
            }

            if (updateOffsetMethod != null) {
                updateOffsetMethod.invoke(backdrop.drawable, targetX, targetY);
            }

            if (setClipRadiusMethod != null) {
                setClipRadiusMethod.invoke(backdrop.drawable, radius);
            }

            backdrop.drawable.setBounds(0, 0, w, h);

            // 1. Draw Authentic Nothing OS Frosted Glass Backdrop
            backdrop.drawable.draw(canvas);

            // 2. Draw Soft Glass Tint Plate (Matching Lockscreen Widgets)
            glassTintPaint.setColor(isDark ? 0x22000000 : 0x24FFFFFF);
            tempRectF.set(0, 0, w, h);
            canvas.drawRoundRect(tempRectF, radius, radius, glassTintPaint);

            // 3. Draw Specular Highlight Glass Border (100% Match with Nothing OS 5.0 Widgets)
            borderPaint.setColor(isDark ? 0x2EFFFFFF : 0x1E000000);
            tempRectF.set(1.0f, 1.0f, w - 1.0f, h - 1.0f);
            canvas.drawRoundRect(tempRectF, radius - 1.0f, radius - 1.0f, borderPaint);

        } catch (Throwable t) {
            Log.e(TAG, "Error drawing frosted shortcut backdrop: " + t.getMessage());
        }
    }

    private static Object getFrostedProvider(Context context, ClassLoader cl) {
        if (providerInstance != null) {
            return providerInstance;
        }

        try {
            if (providerClass == null && cl != null) {
                providerClass = XposedHelpers.findClass("com.nothing.cardhost.frosted.FrostedBackdropProvider", cl);
            }
            if (providerClass == null) {
                return null;
            }

            // 1. Check Companion.getInstance()
            try {
                Field compField = providerClass.getDeclaredField("Companion");
                compField.setAccessible(true);
                Object companion = compField.get(null);
                if (companion != null) {
                    Method getInst = companion.getClass().getMethod("getInstance");
                    Object inst = getInst.invoke(companion);
                    if (inst != null) {
                        providerInstance = inst;
                        return providerInstance;
                    }
                    if (context != null) {
                        Method initMethod = companion.getClass().getMethod("init", Context.class);
                        initMethod.invoke(companion, context);
                        inst = getInst.invoke(companion);
                        if (inst != null) {
                            providerInstance = inst;
                            return providerInstance;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Check static field 'instance'
            try {
                Field instanceField = providerClass.getDeclaredField("instance");
                instanceField.setAccessible(true);
                Object inst = instanceField.get(null);
                if (inst != null) {
                    providerInstance = inst;
                    return providerInstance;
                }
            } catch (Throwable ignored) {}

            // 3. Any static field matching providerClass
            for (Field f : providerClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && providerClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object inst = f.get(null);
                    if (inst != null) {
                        providerInstance = inst;
                        return providerInstance;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error resolving FrostedBackdropProvider: " + t.getMessage());
        }
        return null;
    }

    private static class FrostedShortcutBackdrop {
        final Drawable drawable;
        final int[] screenLoc = new int[2];
        int lastUiMode = -1;

        FrostedShortcutBackdrop(Drawable drawable) {
            this.drawable = drawable;
        }
    }
}
