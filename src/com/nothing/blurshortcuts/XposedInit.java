package com.nothing.blurshortcuts;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

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
    private static final Set<Class<?>> hookedClasses = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean systemUiInitialized = false;

    private static final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glassTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pressedHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF tempRectF = new RectF();

    static {
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
        glassTintPaint.setStyle(Paint.Style.FILL);
        pressedHighlightPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        Log.i(TAG, "NothingBlurShortcuts handleLoadPackage in com.android.systemui");

        // Hook Application.onCreate to ensure complete SystemUI multidex classpath is loaded
        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (systemUiInitialized) return;
                    Application app = (Application) param.thisObject;
                    if (app != null && "com.android.systemui".equals(app.getPackageName())) {
                        systemUiInitialized = true;
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

        // 1. Resolve Nothing OS Frosted Backdrop classes
        if (providerClass == null) {
            try {
                providerClass = XposedHelpers.findClass("com.nothing.cardhost.frosted.FrostedBackdropProvider", cl);
            } catch (Throwable t) {
                try {
                    providerClass = XposedHelpers.findClass("com.nothing.systemui.widget.NTKeyguardFrostedBackdrop", cl);
                } catch (Throwable ignored) {}
            }
        }

        if (drawableClass == null) {
            try {
                drawableClass = XposedHelpers.findClass("com.nothing.commBase.frosted.stdview.FrostedBackdropDrawable", cl);
                setClipRadiusMethod = drawableClass.getMethod("setClipRadius", float.class);
                updateOffsetMethod = drawableClass.getMethod("updateOffset", float.class, float.class);
                completeTransitionNowMethod = drawableClass.getMethod("completeTransitionNow");
                onThemeChangedMethod = drawableClass.getMethod("onThemeChanged");
                try {
                    setAnimateEntranceMethod = drawableClass.getMethod("setAnimateEntrance", boolean.class);
                } catch (Throwable ignored) {}
                Log.i(TAG, "Successfully resolved FrostedBackdropDrawable methods!");
            } catch (Throwable t) {
                Log.w(TAG, "FrostedBackdropDrawable lookup pending: " + t.getMessage());
            }
        }

        // 2. Hook KeyguardBottomAreaView lifecycle
        try {
            Class<?> bottomAreaClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.KeyguardBottomAreaView", cl);

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
            Log.i(TAG, "Successfully hooked KeyguardBottomAreaView!");
        } catch (Throwable t) {
            Log.w(TAG, "KeyguardBottomAreaView lookup pending: " + t.getMessage());
        }

        // 3. Hook KeyguardQuickAffordanceViewBinder to completely strip stock opaque background & tint
        try {
            Class<?> binderClass = XposedHelpers.findClass("com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder", cl);

            XposedBridge.hookAllMethods(binderClass, "bind", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                        registerShortcutButton((View) param.args[0]);
                    }
                }
            });

            // Suppress stock opaque tinting
            XposedBridge.hookAllMethods(binderClass, "updateBackgroundTint", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

            // Suppress stock layer background logic
            XposedBridge.hookAllMethods(binderClass, "updateBackground", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

            Log.i(TAG, "Successfully hooked KeyguardQuickAffordanceViewBinder (opaque tint suppressed)!");
        } catch (Throwable t) {
            Log.w(TAG, "KeyguardQuickAffordanceViewBinder lookup pending: " + t.getMessage());
        }

        // 4. Hook ImageView.onDraw and View.draw to guarantee complete transparency of the button plate
        try {
            // Hook draw() to purge any stock background right before drawBackground()
            XposedBridge.hookAllMethods(ImageView.class, "draw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (shortcutButtons.isEmpty()) return;
                    View view = (View) param.thisObject;
                    if (!isShortcutButton(view)) return;

                    if (view.getBackground() != null) {
                        view.setBackground(null);
                    }
                }
            });

            // Hook onDraw() to draw the authentic transparent frosted glass backdrop
            XposedBridge.hookAllMethods(ImageView.class, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (shortcutButtons.isEmpty()) return;
                    View view = (View) param.thisObject;
                    if (!isShortcutButton(view)) return;

                    Canvas canvas = (Canvas) param.args[0];
                    if (canvas != null) {
                        renderFrostedBackdrop(view, canvas);
                    }
                }
            });

            // Prevent KeyguardQuickAffordanceViewBinder from re-applying keyguard_bottom_affordance_bg
            XposedBridge.hookAllMethods(ImageView.class, "setBackgroundResource", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    if (isShortcutButton(view)) {
                        param.setResult(null); // Block stock background resource
                    }
                }
            });

            XposedBridge.hookAllMethods(ImageView.class, "setBackgroundDrawable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    if (isShortcutButton(view)) {
                        param.setResult(null); // Block stock background drawable
                    }
                }
            });

            Log.i(TAG, "Successfully hooked ImageView draw/onDraw/setBackground for shortcuts!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ImageView methods: " + t.getMessage(), t);
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

        // Completely remove stock opaque background
        button.setBackground(null);
        button.setBackgroundTintList(null);

        ensureClassHooked(button.getClass());
    }

    private static synchronized void ensureClassHooked(Class<?> clazz) {
        if (clazz == null || clazz == ImageView.class || clazz == View.class) return;
        if (hookedClasses.add(clazz)) {
            try {
                XposedBridge.hookAllMethods(clazz, "draw", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.thisObject;
                        if (!isShortcutButton(view)) return;
                        if (view.getBackground() != null) {
                            view.setBackground(null);
                        }
                    }
                });

                XposedBridge.hookAllMethods(clazz, "onDraw", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.thisObject;
                        if (!isShortcutButton(view)) return;
                        Canvas canvas = (Canvas) param.args[0];
                        if (canvas != null) {
                            renderFrostedBackdrop(view, canvas);
                        }
                    }
                });

                XposedBridge.hookAllMethods(clazz, "setBackgroundResource", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.thisObject;
                        if (isShortcutButton(view)) {
                            param.setResult(null);
                        }
                    }
                });

                XposedBridge.hookAllMethods(clazz, "setBackgroundDrawable", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.thisObject;
                        if (isShortcutButton(view)) {
                            param.setResult(null);
                        }
                    }
                });

                Log.i(TAG, "Dynamically hooked methods for subclass: " + clazz.getName());
            } catch (Throwable t) {
                Log.w(TAG, "Subclass hook skipped: " + t.getMessage());
            }
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

        // Ensure stock background is not drawn
        if (view.getBackground() != null) {
            view.setBackground(null);
        }

        Context context = view.getContext();
        int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);
        float radius = Math.min(w, h) / 2.0f;

        Object provider = getFrostedProvider(context, view.getClass().getClassLoader());

        FrostedShortcutBackdrop backdrop = backdropMap.get(view);
        if (backdrop == null && provider != null && drawableClass != null) {
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
            }
        }

        // 1. Draw Live Hardware Wallpaper Frosted Blur (if provider available)
        if (backdrop != null && backdrop.drawable != null) {
            view.getLocationOnScreen(backdrop.screenLoc);
            float targetX = (float) backdrop.screenLoc[0];
            float targetY = (float) backdrop.screenLoc[1];

            try {
                if (onThemeChangedMethod != null && backdrop.lastUiMode != uiMode) {
                    onThemeChangedMethod.invoke(backdrop.drawable);
                    backdrop.lastUiMode = uiMode;
                }

                if (updateOffsetMethod != null) {
                    updateOffsetMethod.invoke(backdrop.drawable, targetX, targetY);
                }

                if (setClipRadiusMethod != null) {
                    setClipRadiusMethod.invoke(backdrop.drawable, radius);
                }

                backdrop.drawable.setBounds(0, 0, w, h);
                backdrop.drawable.draw(canvas);
            } catch (Throwable t) {
                Log.e(TAG, "Error drawing frosted drawable: " + t.getMessage());
            }
        }

        // 2. Draw Pure Translucent Frosted Glass Plate (Identical transparency to 1x1 Lockscreen Widgets)
        // High transparency: in Dark Mode, soft translucent white frost; in Light Mode, translucent milky glass
        glassTintPaint.setColor(isDark ? 0x1AFFFFFF : 0x2AFFFFFF);
        tempRectF.set(0, 0, w, h);
        canvas.drawRoundRect(tempRectF, radius, radius, glassTintPaint);

        // 3. Draw Touch / Press Highlight (lights up softly when pressed)
        if (view.isPressed()) {
            pressedHighlightPaint.setColor(isDark ? 0x35FFFFFF : 0x40FFFFFF);
            canvas.drawRoundRect(tempRectF, radius, radius, pressedHighlightPaint);
        }

        // 4. Draw Specular Highlight Glass Border (Thin 1.5px outline matching Nothing OS widgets)
        borderPaint.setColor(isDark ? 0x33FFFFFF : 0x22000000);
        tempRectF.set(1.0f, 1.0f, w - 1.0f, h - 1.0f);
        canvas.drawRoundRect(tempRectF, radius - 1.0f, radius - 1.0f, borderPaint);
    }

    private static Object getFrostedProvider(Context context, ClassLoader cl) {
        if (providerInstance != null) {
            return providerInstance;
        }

        try {
            if (providerClass == null && cl != null) {
                try {
                    providerClass = XposedHelpers.findClass("com.nothing.cardhost.frosted.FrostedBackdropProvider", cl);
                } catch (Throwable ignored) {
                    providerClass = XposedHelpers.findClass("com.nothing.systemui.widget.NTKeyguardFrostedBackdrop", cl);
                }
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
