package com.nothing.blurshortcuts;

import android.animation.ValueAnimator;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
    private static volatile Context sAppContext = null;
    private static volatile View sNsslView = null;
    private static volatile View sRootWindowView = null;
    private static volatile Object sStatusBarStateController = null;
    private static volatile Object sAmbientState = null;
    private static volatile Object sStatusBarKeyguardViewManager = null;
    private static volatile Object sKeyguardStateController = null;
    private static volatile Object sPrimaryBouncerInteractor = null;
    private static volatile Object sSysUiState = null;
    private static volatile int sStatusBarState = 1; // 1 = KEYGUARD, 0 = SHADE, 2 = SHADE_LOCKED
    private static volatile float sLsToShadeProgress = 0.0f;
    private static volatile float sFractionToShade = 0.0f;
    private static volatile boolean sScreenAwake = true;
    private static volatile boolean sBouncerShowing = false;
    private static volatile boolean sInitialized = false;

    // Global tracking of all notification background views for instant zero-latency blur sleep/wake
    private static final Set<View> sAllNotificationBackgrounds = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));
    private static volatile Handler sMainHandler = null;

    private static Handler getMainHandler() {
        if (sMainHandler == null) {
            synchronized (XposedInit.class) {
                if (sMainHandler == null) {
                    try {
                        sMainHandler = new Handler(Looper.getMainLooper());
                    } catch (Throwable ignored) {}
                }
            }
        }
        return sMainHandler;
    }

    // Fast reusable draw objects (UI Thread only)
    private static final RectF sDrawRectF = new RectF();
    private static final Path sClipPath = new Path();
    private static final float[] sCornerRadii = new float[8];
    private static final Matrix sShortcutMatrix = new Matrix();
    private static final float[] sShortcutMatrixValues = new float[9];

    // Pre-reflected fast fields & methods for 120 FPS notification rendering
    private static volatile boolean sNotificationBlursCleared = false;
    private static volatile boolean sAmbientStateLookupAttempted = false;

    private static Field sStateField = null;
    private static Field sLStoShadeProgressField = null;
    private static Field sFractionToShadeField = null;
    private static Field sBackgroundTopField = null;
    private static Field sActualWidthField = null;
    private static Field sActualHeightField = null;
    private static Field sClipTopAmountField = null;
    private static Field sClipBottomAmountField = null;
    private static Field sBackgroundBlurDrawableField = null;
    private static Field sBackgroundNormalField = null;
    private static Field sBackgroundDimmedField = null;
    private static Field sShowNoBackgroundField = null;
    private static Field sTintColorField = null;
    private static Field sNormalColorField = null;
    private static Field sOnKeyguardField = null;
    private static Field sBackgroundField = null;

    private static Method sIsShowNoBackgroundMethod = null;
    private static Method sGetActualHeightMethod = null;
    private static Method sCreateBackgroundBlurDrawableMethod = null;
    private static Method sSetBlurRadiusMethod = null;
    private static Method sSetCornerRadiusMethod = null;
    private static Method sSetCornerRadiusSingleMethod = null;
    private static Method sGetNormalColorMethod = null;
    private static Method sGetViewRootImplMethod = null;
    private static Method sGetFlagsMethod = null;
    private static Method sNsslIsExpandedMethod = null;

    private static Method sIsPinnedMethod = null;
    private static Method sIsPinnedAndExpandedMethod = null;
    private static Method sIsHeadsUpMethod = null;
    private static Method sIsHeadsUpStateMethod = null;
    private static Method sIsHeadsUpAnimatingAwayMethod = null;
    private static Method sIsOnKeyguardMethod = null;
    private static Method sIsColorizedMethod = null;
    private static Method sGetTopCornerRadiusMethod = null;
    private static Method sGetBottomCornerRadiusMethod = null;
    private static Method sSetColorMethod = null;
    private static Method sGetOutlineRectMethod = null;
    private static Method sGetOutlineAlphaMethod = null;
    private static Method sHasCustomOutlineMethod = null;
    private static Method sGetAppearAnimationFractionMethod = null;
    private static Method sIsDrawingAppearAnimationMethod = null;
    private static Field sShelfActualWidthField = null;

    private static Object sLucentColorUtilsInstance = null;
    private static Method sGetLucentColorMethod = null;

    // Fast dynamic frosted color cache (invalidated on theme changes)
    private static volatile int sCachedFrostedColorDark = 0;
    private static volatile int sCachedFrostedColorLight = 0;
    private static volatile int sLastColorCacheUiMode = -1;

    // Cached Volume Dialog resource IDs for zero-overhead styling
    private static int sIdExpandContainer = -1;
    private static int sIdVolumeBg = -1;
    private static int sIdRingerBg = -1;
    private static int sIdCaptions = -1;
    private static int sIdA11y = -1;
    private static int sIdAssistant = -1;

    // Cached Compose native canvas reflection
    private static Method sComposeGetInternalCanvasMethod = null;
    private static Field sComposeInternalCanvasField = null;
    private static volatile boolean sComposeCanvasLookupDone = false;

    private static final Paint glassTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glassBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint solidBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        glassTintPaint.setStyle(Paint.Style.FILL);
        glassBorderPaint.setStyle(Paint.Style.STROKE);
        glassBorderPaint.setStrokeWidth(2.0f); // 2px crisp specular border
        solidBgPaint.setStyle(Paint.Style.FILL);
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
                        sAppContext = app;
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

            XposedBridge.hookAllMethods(flagsClass, "lockscreenBlurForNotifications", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(true);
                }
            });

            Log.e(TAG, "Successfully enabled Flags.enableLockscreenBlur, blurOnMoreSurfaces & lockscreenBlurForNotifications!");
        } catch (Throwable t) {
            Log.e(TAG, "Flags hook check: " + t.getMessage());
        }

        // 2. Discover FrostedBackdropDrawable and FrostedBackdropProvider
        try {
            providerClass = cl.loadClass("com.nothing.cardhost.frosted.FrostedBackdropProvider");
            XposedBridge.hookAllConstructors(providerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    backdropProvider = param.thisObject;
                    Log.e(TAG, "Captured FrostedBackdropProvider via constructor: " + backdropProvider);
                }
            });
            try {
                Object companion = XposedHelpers.getStaticObjectField(providerClass, "Companion");
                backdropProvider = XposedHelpers.callMethod(companion, "getInstance");
            } catch (Throwable ignored) {}
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

        try {
            Class<?> lucentCls = cl.loadClass("com.nothing.commBase.lucent.LucentColorUtils");
            sLucentColorUtilsInstance = XposedHelpers.getStaticObjectField(lucentCls, "INSTANCE");
            sGetLucentColorMethod = lucentCls.getMethod("getLucentColor", Context.class, String.class);
            sGetLucentColorMethod.setAccessible(true);
            Log.e(TAG, "LucentColorUtils reflected successfully!");
        } catch (Throwable t) {
            Log.e(TAG, "LucentColorUtils check: " + t.getMessage());
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
                        int maxCheck = Math.min(st.length, 12);
                        for (int i = 0; i < maxCheck; i++) {
                            if (st[i].getClassName().contains("ShortcutElementProvider")) {
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
                                    Context ctx = sAppContext;
                                    if (ctx == null) {
                                        try {
                                            ctx = (Context) XposedHelpers.callStaticMethod(
                                                XposedHelpers.findClass("android.app.ActivityThread", null),
                                                "currentApplication"
                                            );
                                            if (ctx != null) sAppContext = ctx;
                                        } catch (Throwable ignored) {}
                                    }

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

                                    nativeCanvas.getMatrix(sShortcutMatrix);
                                    sShortcutMatrix.getValues(sShortcutMatrixValues);
                                    float screenX = sShortcutMatrixValues[Matrix.MTRANS_X];
                                    float screenY = sShortcutMatrixValues[Matrix.MTRANS_Y];

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
            // Instant zero-latency sleep/wake response
            try {
                Class<?> kvmCls = cl.loadClass("com.android.systemui.keyguard.KeyguardViewMediator");
                XC_MethodHook sleepKvmHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleGoingToSleep();
                    }
                };
                XC_MethodHook wakeKvmHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleWakingUp();
                    }
                };
                try { XposedBridge.hookAllMethods(kvmCls, "onStartedGoingToSleep", sleepKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "handleNotifyStartedGoingToSleep", sleepKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "onFinishedGoingToSleep", sleepKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "onStartedWakingUp", wakeKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "handleNotifyWakingUp", wakeKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "onScreenTurningOn", wakeKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "onScreenTurnedOn", wakeKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "onScreenTurningOff", sleepKvmHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "onScreenTurnedOff", sleepKvmHook); } catch (Throwable ignored) {}
                XC_MethodHook bouncerDoneHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        onBouncerShowingChanged(false);
                    }
                };
                try { XposedBridge.hookAllMethods(kvmCls, "sendUserPresent", bouncerDoneHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "keyguardDone", bouncerDoneHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kvmCls, "handleKeyguardDone", bouncerDoneHook); } catch (Throwable ignored) {}
                Log.e(TAG, "KeyguardViewMediator hooked for instant sleep/wake response!");
            } catch (Throwable t) {
                Log.e(TAG, "KeyguardViewMediator hook check: " + t.getMessage());
            }

            try {
                Class<?> kumCls = cl.loadClass("com.android.keyguard.KeyguardUpdateMonitor");
                XC_MethodHook sleepKumHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleGoingToSleep();
                    }
                };
                XC_MethodHook wakeKumHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleWakingUp();
                    }
                };
                try { XposedBridge.hookAllMethods(kumCls, "dispatchStartedGoingToSleep", sleepKumHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kumCls, "dispatchFinishedGoingToSleep", sleepKumHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kumCls, "dispatchStartedWakingUp", wakeKumHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kumCls, "dispatchScreenTurningOn", wakeKumHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kumCls, "dispatchScreenTurnedOn", wakeKumHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kumCls, "dispatchScreenTurningOff", sleepKumHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(kumCls, "dispatchScreenTurnedOff", sleepKumHook); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            try {
                Class<?> slCls = cl.loadClass("com.android.systemui.keyguard.ScreenLifecycle");
                XC_MethodHook sleepSlHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleGoingToSleep();
                    }
                };
                XC_MethodHook wakeSlHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleWakingUp();
                    }
                };
                try { XposedBridge.hookAllMethods(slCls, "dispatchScreenTurningOff", sleepSlHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(slCls, "dispatchScreenTurnedOff", sleepSlHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(slCls, "dispatchScreenTurningOn", wakeSlHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(slCls, "dispatchScreenTurnedOn", wakeSlHook); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            try {
                Class<?> wlCls = cl.loadClass("com.android.systemui.keyguard.WakefulnessLifecycle");
                XC_MethodHook sleepWlHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleGoingToSleep();
                    }
                };
                XC_MethodHook wakeWlHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleWakingUp();
                    }
                };
                try { XposedBridge.hookAllMethods(wlCls, "dispatchStartedGoingToSleep", sleepWlHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(wlCls, "dispatchFinishedGoingToSleep", sleepWlHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(wlCls, "dispatchStartedWakingUp", wakeWlHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(wlCls, "dispatchFinishedWakingUp", wakeWlHook); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Track Bouncer (PIN/password screen) state to instantly remove blur when bouncer is active
            String[] sysUiStateClassNames = {
                "com.android.systemui.model.SysUiStateImpl",
                "com.android.systemui.model.SysUiState"
            };
            for (String clsName : sysUiStateClassNames) {
                try {
                    Class<?> targetCls = cl.loadClass(clsName);
                    try {
                        sGetFlagsMethod = targetCls.getMethod("getFlags");
                        sGetFlagsMethod.setAccessible(true);
                    } catch (Throwable ignored) {}
                    XposedBridge.hookAllConstructors(targetCls, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sSysUiState = param.thisObject;
                            Log.e(TAG, "SysUiState constructor captured: " + param.thisObject.getClass().getName());
                        }
                    });
                    XposedBridge.hookAllMethods(targetCls, "setFlag", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sSysUiState = param.thisObject;
                            if (param.args.length >= 2 && param.args[0] instanceof Long && param.args[1] instanceof Boolean) {
                                long flag = (Long) param.args[0];
                                boolean enabled = (Boolean) param.args[1];
                                if ((flag & 8L) != 0) {
                                    Log.e(TAG, "SysUiState.setFlag(8L, " + enabled + ")");
                                    onBouncerShowingChanged(enabled, "SysUiState.setFlag");
                                }
                            }
                        }
                    });
                    XposedBridge.hookAllMethods(targetCls, "commitUpdate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sSysUiState = param.thisObject;
                            try {
                                long flags = (sGetFlagsMethod != null) ? (Long) sGetFlagsMethod.invoke(param.thisObject) : (Long) XposedHelpers.callMethod(param.thisObject, "getFlags");
                                boolean bouncer = (flags & 8L) != 0;
                                if (bouncer != sBouncerShowing) {
                                    Log.e(TAG, "SysUiState.commitUpdate bouncer=" + bouncer);
                                    onBouncerShowingChanged(bouncer, "SysUiState.commitUpdate");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    try {
                        XposedBridge.hookAllMethods(targetCls, "notifyAndSetSystemUiStateChanged", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                sSysUiState = param.thisObject;
                                if (param.args.length >= 2 && param.args[0] instanceof Long && param.args[1] instanceof Long) {
                                    long oldFlags = (Long) param.args[0];
                                    long newFlags = (Long) param.args[1];
                                    if ((oldFlags & 8L) != (newFlags & 8L)) {
                                        boolean bouncer = (newFlags & 8L) != 0;
                                        Log.e(TAG, "notifyAndSetSystemUiStateChanged bouncer=" + bouncer);
                                        onBouncerShowingChanged(bouncer, "notifyAndSetSystemUiStateChanged");
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }

            try {
                Class<?> scrimCls = cl.loadClass("com.android.systemui.statusbar.phone.ScrimController");
                XposedBridge.hookAllMethods(scrimCls, "transitionTo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] != null) {
                            String stateName = param.args[0].toString();
                            if (stateName.contains("BOUNCER")) {
                                Log.e(TAG, "ScrimController transitionTo BOUNCER: " + stateName);
                                onBouncerShowingChanged(true);
                            } else if (stateName.equals("KEYGUARD")) {
                                Log.e(TAG, "ScrimController transitionTo KEYGUARD");
                                onBouncerShowingChanged(false);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> skvmCls = cl.loadClass("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
                XposedBridge.hookAllConstructors(skvmCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sStatusBarKeyguardViewManager = param.thisObject;
                    }
                });
                XC_MethodHook bouncerShowHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "StatusBarKeyguardViewManager show hook: " + param.method.getName());
                        onBouncerShowingChanged(true);
                    }
                };
                XC_MethodHook bouncerHideHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "StatusBarKeyguardViewManager hide hook: " + param.method.getName());
                        onBouncerShowingChanged(false);
                    }
                };
                try { XposedBridge.hookAllMethods(skvmCls, "showPrimaryBouncer", bouncerShowHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(skvmCls, "showBouncer", bouncerShowHook); } catch (Throwable ignored) {}
                try {
                    XposedBridge.hookAllMethods(skvmCls, "showBouncerOrKeyguard", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                boolean b = (Boolean) XposedHelpers.callMethod(param.thisObject, "isBouncerShowing");
                                onBouncerShowingChanged(b);
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(skvmCls, "beginShowingBouncer", bouncerShowHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(skvmCls, "hideBouncer", bouncerHideHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(skvmCls, "hideAlternateBouncer", bouncerHideHook); } catch (Throwable ignored) {}
                try {
                    XposedBridge.hookAllMethods(skvmCls, "setBouncerShowing", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                                onBouncerShowingChanged((Boolean) param.args[0]);
                            }
                        }
                    });
                } catch (Throwable ignored) {}
                try {
                    XposedBridge.hookAllMethods(skvmCls, "onBouncerVisibilityChanged", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                boolean b = (Boolean) XposedHelpers.callMethod(param.thisObject, "isBouncerShowing");
                                onBouncerShowingChanged(b);
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Android 15 PrimaryBouncerInteractor & BouncerInteractor
            try {
                Class<?> pbiCls = cl.loadClass("com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor");
                XposedBridge.hookAllConstructors(pbiCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sPrimaryBouncerInteractor = param.thisObject;
                    }
                });
                XposedBridge.hookAllMethods(pbiCls, "show", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "PrimaryBouncerInteractor.show called!");
                        onBouncerShowingChanged(true);
                    }
                });
                XposedBridge.hookAllMethods(pbiCls, "hide", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "PrimaryBouncerInteractor.hide called!");
                        onBouncerShowingChanged(false);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> bouncerRepoCls = cl.loadClass("com.android.systemui.bouncer.data.repository.KeyguardBouncerRepositoryImpl");
                XposedBridge.hookAllMethods(bouncerRepoCls, "setPrimaryShow", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "KeyguardBouncerRepositoryImpl.setPrimaryShow");
                        onBouncerShowingChanged(true);
                    }
                });
                XposedBridge.hookAllMethods(bouncerRepoCls, "setPrimaryStartingToHide", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "KeyguardBouncerRepositoryImpl.setPrimaryStartingToHide");
                        onBouncerShowingChanged(false);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> kscContainerCls = cl.loadClass("com.android.keyguard.KeyguardSecurityContainer");
                XposedBridge.hookAllMethods(kscContainerCls, "setVisibility", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int vis = (Integer) param.args[0];
                            onBouncerShowingChanged(vis == View.VISIBLE);
                        }
                    }
                });
                XposedBridge.hookAllMethods(kscContainerCls, "onVisibilityAggregated", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                            onBouncerShowingChanged((Boolean) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(kscContainerCls, "dispatchDraw", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sBouncerShowing) {
                            onBouncerShowingChanged(true);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> ksccCls = cl.loadClass("com.android.keyguard.KeyguardSecurityContainerController");
                XC_MethodHook showBouncerHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        onBouncerShowingChanged(true);
                    }
                };
                try { XposedBridge.hookAllMethods(ksccCls, "show", showBouncerHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(ksccCls, "showPrimaryBouncer", showBouncerHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(ksccCls, "showBouncer", showBouncerHook); } catch (Throwable ignored) {}
                try {
                    XposedBridge.hookAllMethods(ksccCls, "hide", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            onBouncerShowingChanged(false);
                        }
                    });
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Track Status Bar State via StatusBarStateControllerImpl
            try {
                Class<?> sscCls = cl.loadClass("com.android.systemui.statusbar.StatusBarStateControllerImpl");
                try { sStateField = sscCls.getDeclaredField("mState"); sStateField.setAccessible(true); } catch (Throwable ignored) {}
                XposedBridge.hookAllConstructors(sscCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sStatusBarStateController = param.thisObject;
                        try {
                            if (sStateField != null) sStatusBarState = sStateField.getInt(param.thisObject);
                            else sStatusBarState = (Integer) XposedHelpers.getObjectField(param.thisObject, "mState");
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.hookAllMethods(sscCls, "setState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sStatusBarStateController = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(sscCls, "onStatusBarStateChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sStatusBarStateController = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Track state via AmbientState
            try {
                Class<?> asCls = cl.loadClass("com.android.systemui.statusbar.notification.stack.AmbientState");
                try { sLStoShadeProgressField = asCls.getDeclaredField("mLStoShadeProgress"); sLStoShadeProgressField.setAccessible(true); } catch (Throwable ignored) {}
                try { sFractionToShadeField = asCls.getDeclaredField("mFractionToShade"); sFractionToShadeField.setAccessible(true); } catch (Throwable ignored) {}
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
                            onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setLStoShadeProgress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            onLsToShadeProgressChanged((Float) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(asCls, "setFractionToShade", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sAmbientState = param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float f = (Float) param.args[0];
                            sFractionToShade = f;
                            if (f > 0.20f) {
                                if (!sNotificationBlursCleared) {
                                    sNotificationBlursCleared = true;
                                    clearAllNotificationBlurs();
                                }
                            } else if (f <= 0.05f && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                if (sNotificationBlursCleared) {
                                    sNotificationBlursCleared = false;
                                    invalidateNotificationStack();
                                }
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Track state via NotificationStackScrollLayout
            try {
                Class<?> nsslCls = cl.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
                try { sNsslIsExpandedMethod = nsslCls.getMethod("isExpanded"); sNsslIsExpandedMethod.setAccessible(true); } catch (Throwable ignored) {}
                XposedBridge.hookAllConstructors(nsslCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setStatusBarState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setLStoShadeProgress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            onLsToShadeProgressChanged((Float) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setFractionToShade", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float f = (Float) param.args[0];
                            sFractionToShade = f;
                            if (f > 0.20f) {
                                if (!sNotificationBlursCleared) {
                                    sNotificationBlursCleared = true;
                                    clearAllNotificationBlurs();
                                }
                            } else if (f <= 0.05f && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                if (sNotificationBlursCleared) {
                                    sNotificationBlursCleared = false;
                                    invalidateNotificationStack();
                                }
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setAlpha", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float alpha = (Float) param.args[0];
                            if (alpha < 0.05f && sScreenAwake) {
                                clearAllNotificationBlurs();
                            } else if (alpha > 0.05f && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                invalidateNotificationStack();
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setAlphaForLockscreenFadeIn", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float alpha = (Float) param.args[0];
                            if (alpha < 0.05f && sScreenAwake) {
                                clearAllNotificationBlurs();
                            } else if (alpha > 0.05f && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                invalidateNotificationStack();
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(nsslCls, "setVisibility", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sNsslView = (View) param.thisObject;
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int vis = (Integer) param.args[0];
                            if (vis != View.VISIBLE && sScreenAwake) {
                                clearAllNotificationBlurs();
                            } else if (vis == View.VISIBLE && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                invalidateNotificationStack();
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                Class<?> sncCls = cl.loadClass("com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer");
                XposedBridge.hookAllMethods(sncCls, "setAlpha", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float alpha = (Float) param.args[0];
                            if (alpha < 0.05f && sScreenAwake) {
                                clearAllNotificationBlurs();
                            } else if (alpha > 0.05f && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                invalidateNotificationStack();
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(sncCls, "setVisibility", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int vis = (Integer) param.args[0];
                            if (vis != View.VISIBLE && sScreenAwake) {
                                clearAllNotificationBlurs();
                            } else if (vis == View.VISIBLE && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                invalidateNotificationStack();
                            }
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
                                onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            try {
                Class<?> csCls = cl.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesImpl");
                XposedBridge.hookAllMethods(csCls, "setBarState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (sStatusBarStateController == null) {
                            try { sStatusBarStateController = XposedHelpers.getObjectField(param.thisObject, "mStatusBarStateController"); } catch (Throwable ignored) {}
                        }
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                        }
                    }
                });
                try {
                    XposedBridge.hookAllMethods(csCls, "showBouncerOrUnlock", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            onBouncerShowingChanged(true);
                        }
                    });
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            try {
                Class<?> nsslControllerCls = cl.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController");
                XposedBridge.hookAllMethods(nsslControllerCls, "setStatusBarState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (sStatusBarStateController == null) {
                            try { sStatusBarStateController = XposedHelpers.getObjectField(param.thisObject, "mStatusBarStateController"); } catch (Throwable ignored) {}
                        }
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            onStatusBarStateChanged(sStatusBarState, (Integer) param.args[0]);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Pre-reflect methods & fields on ExpandableNotificationRow and NotificationBackgroundView
            try { sIsPinnedMethod = notifRowClass.getMethod("isPinned"); sIsPinnedMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsPinnedAndExpandedMethod = notifRowClass.getMethod("isPinnedAndExpanded"); sIsPinnedAndExpandedMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsHeadsUpMethod = notifRowClass.getMethod("isHeadsUp"); sIsHeadsUpMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsHeadsUpStateMethod = notifRowClass.getMethod("isHeadsUpState"); sIsHeadsUpStateMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsHeadsUpAnimatingAwayMethod = notifRowClass.getMethod("isHeadsUpAnimatingAway"); sIsHeadsUpAnimatingAwayMethod.setAccessible(true); } catch (Throwable ignored) {}
            try {
                Class<?> anvCls = cl.loadClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView");
                sIsOnKeyguardMethod = anvCls.getMethod("isOnKeyguard");
                sIsOnKeyguardMethod.setAccessible(true);
                try { sGetAppearAnimationFractionMethod = anvCls.getMethod("getAppearAnimationFraction"); sGetAppearAnimationFractionMethod.setAccessible(true); } catch (Throwable ignored) {}
                try { sIsDrawingAppearAnimationMethod = anvCls.getMethod("isDrawingAppearAnimation"); sIsDrawingAppearAnimationMethod.setAccessible(true); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            try {
                Class<?> eovCls = cl.loadClass("com.android.systemui.statusbar.notification.row.ExpandableOutlineView");
                try { sGetOutlineRectMethod = eovCls.getMethod("getOutlineRect"); sGetOutlineRectMethod.setAccessible(true); } catch (Throwable ignored) {}
                try { sGetOutlineAlphaMethod = eovCls.getMethod("getOutlineAlpha"); sGetOutlineAlphaMethod.setAccessible(true); } catch (Throwable ignored) {}
                try { sHasCustomOutlineMethod = eovCls.getMethod("hasCustomOutline"); sHasCustomOutlineMethod.setAccessible(true); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            try { sIsColorizedMethod = notifRowClass.getMethod("isColorized"); sIsColorizedMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetTopCornerRadiusMethod = notifRowClass.getMethod("getTopCornerRadius"); sGetTopCornerRadiusMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetBottomCornerRadiusMethod = notifRowClass.getMethod("getBottomCornerRadius"); sGetBottomCornerRadiusMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetActualHeightMethod = notifRowClass.getMethod("getActualHeight"); sGetActualHeightMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sIsShowNoBackgroundMethod = notifRowClass.getMethod("isShowNoBackground"); sIsShowNoBackgroundMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetNormalColorMethod = notifRowClass.getMethod("getNormalColor"); sGetNormalColorMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { sShowNoBackgroundField = notifRowClass.getDeclaredField("mShowNoBackground"); sShowNoBackgroundField.setAccessible(true); } catch (Throwable ignored) {}
            try { sBackgroundNormalField = notifRowClass.getDeclaredField("mBackgroundNormal"); sBackgroundNormalField.setAccessible(true); } catch (Throwable ignored) {}
            try { sBackgroundDimmedField = notifRowClass.getDeclaredField("mBackgroundDimmed"); sBackgroundDimmedField.setAccessible(true); } catch (Throwable ignored) {}

            if (shelfCls != null) {
                try { sShelfActualWidthField = shelfCls.getDeclaredField("mActualWidth"); sShelfActualWidthField.setAccessible(true); } catch (Throwable ignored) {}
            }

            try { sBackgroundTopField = notifBgClass.getDeclaredField("mBackgroundTop"); sBackgroundTopField.setAccessible(true); } catch (Throwable ignored) {}
            try { sActualWidthField = notifBgClass.getDeclaredField("mActualWidth"); sActualWidthField.setAccessible(true); } catch (Throwable ignored) {}
            try { sActualHeightField = notifBgClass.getDeclaredField("mActualHeight"); sActualHeightField.setAccessible(true); } catch (Throwable ignored) {}
            try { sClipTopAmountField = notifBgClass.getDeclaredField("mClipTopAmount"); sClipTopAmountField.setAccessible(true); } catch (Throwable ignored) {}
            try { sClipBottomAmountField = notifBgClass.getDeclaredField("mClipBottomAmount"); sClipBottomAmountField.setAccessible(true); } catch (Throwable ignored) {}
            try { sBackgroundBlurDrawableField = notifBgClass.getDeclaredField("mBackgroundBlurDrawable"); sBackgroundBlurDrawableField.setAccessible(true); } catch (Throwable ignored) {}
            try { sTintColorField = notifBgClass.getDeclaredField("mTintColor"); sTintColorField.setAccessible(true); } catch (Throwable ignored) {}
            try { sNormalColorField = notifBgClass.getDeclaredField("mNormalColor"); sNormalColorField.setAccessible(true); } catch (Throwable ignored) {}
            try { sOnKeyguardField = notifBgClass.getDeclaredField("mOnKeyguard"); sOnKeyguardField.setAccessible(true); } catch (Throwable ignored) {}
            try { sBackgroundField = notifBgClass.getDeclaredField("mBackground"); sBackgroundField.setAccessible(true); } catch (Throwable ignored) {}
            try { sGetViewRootImplMethod = View.class.getMethod("getViewRootImpl"); sGetViewRootImplMethod.setAccessible(true); } catch (Throwable ignored) {}

            try {
                Class<?> bbdCls = cl.loadClass("com.android.internal.graphics.drawable.BackgroundBlurDrawable");
                try { sSetBlurRadiusMethod = bbdCls.getMethod("setBlurRadius", int.class); sSetBlurRadiusMethod.setAccessible(true); } catch (Throwable ignored) {}
                try { sSetCornerRadiusMethod = bbdCls.getMethod("setCornerRadius", float.class, float.class, float.class, float.class); sSetCornerRadiusMethod.setAccessible(true); } catch (Throwable ignored) {}
                try { sSetCornerRadiusSingleMethod = bbdCls.getMethod("setCornerRadius", float.class); sSetCornerRadiusSingleMethod.setAccessible(true); } catch (Throwable ignored) {}
                try { sSetColorMethod = bbdCls.getMethod("setColor", int.class); sSetColorMethod.setAccessible(true); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            try {
                Class<?> vriCls = cl.loadClass("android.view.ViewRootImpl");
                sCreateBackgroundBlurDrawableMethod = vriCls.getMethod("createBackgroundBlurDrawable");
                sCreateBackgroundBlurDrawableMethod.setAccessible(true);
            } catch (Throwable ignored) {}

            XC_MethodHook rowExpansionHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View row = (View) param.thisObject;
                    if (row != null) {
                        row.invalidate();
                        if (sBackgroundNormalField != null) {
                            try {
                                View bgNormal = (View) sBackgroundNormalField.get(row);
                                if (bgNormal != null) bgNormal.invalidate();
                            } catch (Throwable ignored) {}
                        }
                        if (sBackgroundDimmedField != null) {
                            try {
                                View bgDimmed = (View) sBackgroundDimmedField.get(row);
                                if (bgDimmed != null) bgDimmed.invalidate();
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            };
            try { XposedBridge.hookAllMethods(notifRowClass, "setUserExpanded", rowExpansionHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setShowingExpanded", rowExpansionHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setGroupExpanded", rowExpansionHook); } catch (Throwable ignored) {}

            XC_MethodHook headsUpAnimatingAwayHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View row = (View) param.thisObject;
                    if (row != null) {
                        boolean animatingAway = false;
                        if (param.args.length > 0 && param.args[0] instanceof Boolean) {
                            animatingAway = (Boolean) param.args[0];
                        }
                        if (!animatingAway) {
                            if (sBackgroundNormalField != null) {
                                try {
                                    View bgNormal = (View) sBackgroundNormalField.get(row);
                                    if (bgNormal != null) cleanupBlur(bgNormal);
                                } catch (Throwable ignored) {}
                            }
                        } else {
                            row.invalidate();
                            if (sBackgroundNormalField != null) {
                                try {
                                    View bgNormal = (View) sBackgroundNormalField.get(row);
                                    if (bgNormal != null) bgNormal.invalidate();
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            };
            try { XposedBridge.hookAllMethods(notifRowClass, "setHeadsUpAnimatingAway", headsUpAnimatingAwayHook); } catch (Throwable ignored) {}

            XC_MethodHook hunMotionHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View row = (View) param.thisObject;
                    if (row != null) {
                        boolean isHun = false;
                        if (sIsPinnedMethod != null) {
                            try { isHun = (Boolean) sIsPinnedMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (!isHun && sIsHeadsUpAnimatingAwayMethod != null) {
                            try { isHun = (Boolean) sIsHeadsUpAnimatingAwayMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (!isHun && sIsHeadsUpMethod != null) {
                            try { isHun = (Boolean) sIsHeadsUpMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (isHun) {
                            if (sBackgroundNormalField != null) {
                                try {
                                    View bgNormal = (View) sBackgroundNormalField.get(row);
                                    if (bgNormal != null) bgNormal.invalidate();
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            };
            try { XposedBridge.hookAllMethods(notifRowClass, "setTranslation", hunMotionHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setActualHeight", hunMotionHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setClipTopAmount", hunMotionHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setClipBottomAmount", hunMotionHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "applyLaunchAnimationParams", hunMotionHook); } catch (Throwable ignored) {}

            XC_MethodHook bgInvalidateHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View bgView = (View) param.thisObject;
                    if (bgView != null) {
                        bgView.invalidate();
                    }
                }
            };
            try { XposedBridge.hookAllMethods(notifBgClass, "setActualHeight", bgInvalidateHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifBgClass, "setClipTopAmount", bgInvalidateHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifBgClass, "setClipBottomAmount", bgInvalidateHook); } catch (Throwable ignored) {}

            try {
                Class<?> vsClass = cl.loadClass("com.android.systemui.statusbar.notification.stack.ViewState");
                XposedBridge.hookAllMethods(vsClass, "applyToView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            View row = (View) param.args[0];
                            if (notifRowClass != null && notifRowClass.isAssignableFrom(row.getClass())) {
                                boolean isHun = false;
                                if (sIsPinnedMethod != null) {
                                    try { isHun = (Boolean) sIsPinnedMethod.invoke(row); } catch (Throwable ignored) {}
                                }
                                if (!isHun && sIsHeadsUpAnimatingAwayMethod != null) {
                                    try { isHun = (Boolean) sIsHeadsUpAnimatingAwayMethod.invoke(row); } catch (Throwable ignored) {}
                                }
                                if (isHun && sBackgroundNormalField != null) {
                                    try {
                                        View bgNormal = (View) sBackgroundNormalField.get(row);
                                        if (bgNormal != null) bgNormal.invalidate();
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook ActivatableNotificationView appear animations to keep blur synchronized frame-by-frame
            try {
                Class<?> anvCls = cl.loadClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView");
                XC_MethodHook appearInvalidateHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View row = (View) param.thisObject;
                        if (row != null && sBackgroundNormalField != null) {
                            try {
                                View bgNormal = (View) sBackgroundNormalField.get(row);
                                if (bgNormal != null) bgNormal.invalidate();
                            } catch (Throwable ignored) {}
                        }
                    }
                };
                try { XposedBridge.hookAllMethods(anvCls, "setAppearAnimationFraction", appearInvalidateHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(anvCls, "startAppearAnimation", appearInvalidateHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(anvCls, "updateAppearRect", appearInvalidateHook); } catch (Throwable ignored) {}
// onAppearAnimationFinished hook removed to avoid premature blur cleanup
            } catch (Throwable ignored) {}

            // Hook PropertyAnimator to track every animated property on notification rows (translationY, scale, alpha)
            try {
                Class<?> paCls = cl.loadClass("com.android.systemui.statusbar.notification.PropertyAnimator");
                Class<?> apCls = cl.loadClass("com.android.systemui.statusbar.notification.AnimatableProperty");
                final Method getAnimTagMethod = apCls.getMethod("getAnimatorTag");
                getAnimTagMethod.setAccessible(true);
                XposedBridge.hookAllMethods(paCls, "startAnimation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && param.args[0] instanceof View && param.args[1] != null) {
                            final View targetView = (View) param.args[0];
                            if (notifRowClass != null && notifRowClass.isAssignableFrom(targetView.getClass())) {
                                int tag = (Integer) getAnimTagMethod.invoke(param.args[1]);
                                Object anim = targetView.getTag(tag);
                                if (anim instanceof ValueAnimator) {
                                    ValueAnimator va = (ValueAnimator) anim;
                                    va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                                        @Override
                                        public void onAnimationUpdate(ValueAnimator animation) {
                                            if (sBackgroundNormalField != null) {
                                                try {
                                                    View bgNormal = (View) sBackgroundNormalField.get(targetView);
                                                    if (bgNormal != null) bgNormal.invalidate();
                                                } catch (Throwable ignored) {}
                                            }
                                        }
                                    });
                                }
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook HeadsUpTouchHelper fly away and touch tracking
            try {
                Class<?> hthCls = cl.loadClass("com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper");
                final Field snoozeFlyAnimField = hthCls.getDeclaredField("mSnoozeFlyAnimator");
                snoozeFlyAnimField.setAccessible(true);
                final Field pickedChildField = hthCls.getDeclaredField("mPickedChild");
                pickedChildField.setAccessible(true);
                XposedBridge.hookAllMethods(hthCls, "startSnoozeFly", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ValueAnimator anim = (ValueAnimator) snoozeFlyAnimField.get(param.thisObject);
                        if (anim != null) {
                            final View row = (View) pickedChildField.get(param.thisObject);
                            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                                @Override
                                public void onAnimationUpdate(ValueAnimator animation) {
                                    if (row != null && sBackgroundNormalField != null) {
                                        try {
                                            View bgNormal = (View) sBackgroundNormalField.get(row);
                                            if (bgNormal != null) bgNormal.invalidate();
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            });
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook HeadsUpTouchHelperEx for Nothing OS pop window and gesture animations
            try {
                Class<?> hthExCls = cl.loadClass("com.nothing.systemui.statusbar.phone.HeadsUpTouchHelperEx");
                XC_MethodHook exUpdateHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[param.args.length - 1] instanceof View) {
                            View row = (View) param.args[param.args.length - 1];
                            if (sBackgroundNormalField != null) {
                                try {
                                    View bgNormal = (View) sBackgroundNormalField.get(row);
                                    if (bgNormal != null) bgNormal.invalidate();
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                };
                try { XposedBridge.hookAllMethods(hthExCls, "updateSourceView", exUpdateHook); } catch (Throwable ignored) {}
                try { XposedBridge.hookAllMethods(hthExCls, "updateChildView", exUpdateHook); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Cleanup blur immediately when row is detached from window
            try {
                XposedBridge.hookAllMethods(notifRowClass, "onDetachedFromWindow", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        View row = (View) param.thisObject;
                        if (row != null && sBackgroundNormalField != null) {
                            try {
                                View bgNormal = (View) sBackgroundNormalField.get(row);
                                if (bgNormal != null) cleanupBlur(bgNormal);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable ignored) {}

            if (notifShelfClass != null) {
                try {
                    XC_MethodHook shelfInvalidateHook = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                View shelf = (View) param.thisObject;
                                if (shelf != null) {
                                    shelf.invalidate();
                                    try {
                                        View bg = (View) XposedHelpers.getObjectField(shelf, "mBackgroundNormal");
                                        if (bg != null) bg.invalidate();
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    };
                    try { XposedBridge.hookAllMethods(notifShelfClass, "updateAppearance", shelfInvalidateHook); } catch (Throwable ignored) {}
                    try { XposedBridge.hookAllMethods(notifShelfClass, "setBackgroundWidth", shelfInvalidateHook); } catch (Throwable ignored) {}
                    try { XposedBridge.hookAllMethods(notifShelfClass, "updateState", shelfInvalidateHook); } catch (Throwable ignored) {}
                    try {
                        XposedBridge.hookAllMethods(notifShelfClass, "setHideBackground", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
                                    param.args[0] = false;
                                }
                            }
                        });
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }

            XC_MethodHook rowHeightHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        View row = (View) param.thisObject;
                        try {
                            View bg = (View) XposedHelpers.getObjectField(row, "mBackgroundNormal");
                            if (bg != null) bg.invalidate();
                        } catch (Throwable ignored) {}
                        try {
                            View bgDimmed = (View) XposedHelpers.getObjectField(row, "mBackgroundDimmed");
                            if (bgDimmed != null) bgDimmed.invalidate();
                        } catch (Throwable ignored) {}
                    }
                }
            };
            try { XposedBridge.hookAllMethods(notifRowClass, "setActualHeight", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setActualHeightAnimating", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setFinalActualHeight", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setUserExpanded", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setSystemExpanded", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "setChildrenExpanded", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "onExpandedByGesture", rowHeightHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifRowClass, "notifyHeightChanged", rowHeightHook); } catch (Throwable ignored) {}

            XC_MethodHook notifDrawHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View bgView = (View) param.thisObject;
                    synchronized (sAllNotificationBackgrounds) {
                        sAllNotificationBackgrounds.add(bgView);
                    }
                    View parent = (View) bgView.getParent();

                    // 1. One-time caching of sStatusBarStateController & sAmbientState if not yet set
                    if (sStatusBarStateController == null) {
                        try {
                            ClassLoader bcl = bgView.getContext().getClassLoader();
                            Class<?> depCls = bcl.loadClass("com.android.systemui.Dependency");
                            Class<?> sscInterface = bcl.loadClass("com.android.systemui.plugins.statusbar.StatusBarStateController");
                            sStatusBarStateController = XposedHelpers.callStaticMethod(depCls, "get", sscInterface);
                        } catch (Throwable ignored) {}
                        if (sStatusBarStateController == null && parent != null) {
                            try {
                                Object ssc = XposedHelpers.getObjectField(parent, "mStatusBarStateController");
                                if (ssc != null) sStatusBarStateController = ssc;
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (sAmbientState == null) {
                        try {
                            ClassLoader bcl = bgView.getContext().getClassLoader();
                            Class<?> depCls = bcl.loadClass("com.android.systemui.Dependency");
                            Class<?> asClass = bcl.loadClass("com.android.systemui.statusbar.notification.stack.AmbientState");
                            sAmbientState = XposedHelpers.callStaticMethod(depCls, "get", asClass);
                        } catch (Throwable ignored) {}
                    }
                    if (sNsslView == null && parent != null) {
                        ViewParent vp = parent.getParent();
                        if (vp instanceof View) {
                            sNsslView = (View) vp;
                        }
                    }
                    if (sRootWindowView == null) {
                        try {
                            View root = bgView.getRootView();
                            if (root != null) sRootWindowView = root;
                        } catch (Throwable ignored) {}
                    }

                    // 2. Read live status bar & shade state directly from controller (fast direct Field read)
                    if (sStatusBarStateController != null && sStateField != null) {
                        try {
                            int st = sStateField.getInt(sStatusBarStateController);
                            if (sStatusBarState != st) {
                                onStatusBarStateChanged(sStatusBarState, st);
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (sSysUiState != null) {
                        try {
                            long flags = (sGetFlagsMethod != null) ? (Long) sGetFlagsMethod.invoke(sSysUiState) : (Long) XposedHelpers.callMethod(sSysUiState, "getFlags");
                            boolean bouncer = (flags & 8L) != 0;
                            if (bouncer != sBouncerShowing) {
                                onBouncerShowingChanged(bouncer, "notifDrawHook.liveCheck");
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (sAmbientState != null) {
                        try {
                            if (sLStoShadeProgressField != null) sLsToShadeProgress = sLStoShadeProgressField.getFloat(sAmbientState);
                            if (sFractionToShadeField != null) sFractionToShade = sFractionToShadeField.getFloat(sAmbientState);
                        } catch (Throwable ignored) {}
                    }

                    // 4. Fast direct method invocation on parent row / shelf
                    View row = parent;
                    while (row != null && notifRowClass != null && !notifRowClass.isAssignableFrom(row.getClass())
                            && !(shelfCls != null && shelfCls.isAssignableFrom(row.getClass()))) {
                        ViewParent vp = row.getParent();
                        row = (vp instanceof View) ? (View) vp : null;
                    }

                    String bgCls = bgView.getClass().getName();
                    boolean isShelf = bgCls.contains("Shelf")
                            || (row != null && row.getClass().getName().contains("Shelf"))
                            || (shelfCls != null && row != null && shelfCls.isAssignableFrom(row.getClass()));

                    // Only mBackgroundNormal should ever draw notification card backgrounds.
                    // mBackgroundDimmed must NEVER draw because it stays at collapsed height and causes double borders.
                    if (row != null && !isShelf) {
                        try {
                            Object normalBg = (sBackgroundNormalField != null) ? sBackgroundNormalField.get(row)
                                    : XposedHelpers.getObjectField(row, "mBackgroundNormal");
                            if (normalBg != null && bgView != normalBg) {
                                cleanupBlur(bgView);
                                param.setResult(null);
                                return;
                            }
                        } catch (Throwable ignored) {}
                    }

                    boolean onKeyguard = (sStatusBarState == 1) && (sLsToShadeProgress <= 0.02f) && (sFractionToShade <= 0.02f);
                    boolean inShade = (sStatusBarState == 2) || (sLsToShadeProgress > 0.02f) || (sFractionToShade > 0.02f);
                    if (sStatusBarStateController != null) {
                        try {
                            int st = (sStateField != null) ? sStateField.getInt(sStatusBarStateController)
                                    : (Integer) XposedHelpers.callMethod(sStatusBarStateController, "getState");
                            if (st != sStatusBarState) {
                                sStatusBarState = st;
                                if (st == 1) {
                                    onKeyguard = (sLsToShadeProgress <= 0.02f) && (sFractionToShade <= 0.02f);
                                    inShade = !onKeyguard;
                                } else {
                                    onKeyguard = false;
                                    inShade = true;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    boolean isHun = false;
                    boolean isColorized = false;
                    if (!isShelf && row != null && notifRowClass.isAssignableFrom(row.getClass())) {
                        if (sIsPinnedMethod != null) {
                            try { isHun = (Boolean) sIsPinnedMethod.invoke(row); } catch (Throwable ignored) {}
                        } else {
                            try { isHun = (Boolean) XposedHelpers.callMethod(row, "isPinned"); } catch (Throwable ignored) {}
                        }
                        if (!isHun && sIsHeadsUpMethod != null) {
                            try { isHun = (Boolean) sIsHeadsUpMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (!isHun && sIsPinnedAndExpandedMethod != null) {
                            try { isHun = (Boolean) sIsPinnedAndExpandedMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (!isHun && sIsHeadsUpStateMethod != null) {
                            try { isHun = (Boolean) sIsHeadsUpStateMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (!isHun && sIsHeadsUpAnimatingAwayMethod != null) {
                            try { isHun = (Boolean) sIsHeadsUpAnimatingAwayMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (!isHun) {
                            try { isHun = (Boolean) XposedHelpers.callMethod(row, "isHeadsUpAnimatingAway"); } catch (Throwable ignored) {}
                        }
                        if (!isHun) {
                            try { isHun = (Boolean) XposedHelpers.callMethod(row, "isHeadsUpOrAnimatingAway"); } catch (Throwable ignored) {}
                        }

                        if (sIsColorizedMethod != null) {
                            try { isColorized = (Boolean) sIsColorizedMethod.invoke(row); } catch (Throwable ignored) {}
                        } else {
                            try { isColorized = (Boolean) XposedHelpers.callMethod(row, "isColorized"); } catch (Throwable ignored) {}
                        }
                    }

                    if (sStatusBarState == 0 && !isHun) {
                        inShade = true;
                    }

                    // 3. Screen awake / Sleep / AOD / Bouncer check & dynamic alpha / dismissal tracking:
                    float stackAlpha = 1.0f;
                    if (sNsslView != null) {
                        try { stackAlpha = sNsslView.getAlpha(); } catch (Throwable ignored) {}
                    }
                    float rowAlpha = 1.0f;
                    if (row != null) {
                        try { rowAlpha = row.getAlpha(); } catch (Throwable ignored) {}
                    } else if (parent != null) {
                        try { rowAlpha = parent.getAlpha(); } catch (Throwable ignored) {}
                    }
                    float bgAlpha = bgView.getAlpha();
                    float effectiveAlpha = Math.max(0f, Math.min(1f, rowAlpha * bgAlpha));

                    float motionFraction = 1.0f;
                    if (isHun && row != null) {
                        float transY = 0f;
                        float transX = 0f;
                        try {
                            transY = row.getTranslationY();
                            if (transY == 0f) {
                                Object t = XposedHelpers.callMethod(row, "getTranslation");
                                if (t instanceof Float) transY = (Float) t;
                            }
                            transX = Math.abs(row.getTranslationX());
                        } catch (Throwable ignored) {}

                        int actualH = 0;
                        if (sActualHeightField != null) {
                            try { actualH = sActualHeightField.getInt(bgView); } catch (Throwable ignored) {}
                        }
                        if (actualH <= 0 && sGetActualHeightMethod != null) {
                            try { actualH = (Integer) sGetActualHeightMethod.invoke(row); } catch (Throwable ignored) {}
                        }
                        if (actualH <= 0) actualH = bgView.getHeight();

                        if (transY < 0f && actualH > 0) {
                            motionFraction = Math.min(motionFraction, Math.max(0f, 1.0f + (transY / (float) actualH)));
                        }
                        int bgW = bgView.getWidth();
                        if (transX > 0f && bgW > 0) {
                            motionFraction = Math.min(motionFraction, Math.max(0f, 1.0f - (transX / (float) bgW)));
                        }
                    }

                    float combinedAlpha = effectiveAlpha * motionFraction;

                    if (!sScreenAwake || sBouncerShowing || stackAlpha < 0.05f || combinedAlpha < 0.03f) {
                        cleanupBlur(bgView);
                        param.setResult(null);
                        return;
                    }

                    // Target determination (Two-View Notification Architecture):
                    // View 1 - Frosted Glass Blur Mode applies to:
                    //   - All notification cards on resting lockscreen (onKeyguard)
                    //   - Collapsed shelf collage capsule on lockscreen (isShelf && onKeyguard)
                    //   - Heads-Up floating banner alerts over apps (isHun && !inShade)
                    // View 2 - Classic Solid Stock System Background Mode applies to:
                    //   - All notifications in Notification Shade menu (inShade == true)
                    //   - Bouncer PIN/password screen
                    //   - Colorized media player with album art
                    boolean isTarget = false;
                    if (sScreenAwake && !sBouncerShowing && !isColorized) {
                        if (isHun) {
                            isTarget = true;
                        } else if (onKeyguard && !inShade) {
                            isTarget = true;
                        }
                    }

                    Canvas canvas = (Canvas) param.args[0];
                    if (canvas == null) return;

                    Context ctx = bgView.getContext();
                    if (ctx == null) return;

                    int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);

                    boolean showNoBg = false;
                    if (row != null) {
                        if (sIsShowNoBackgroundMethod != null) {
                            try { showNoBg = (Boolean) sIsShowNoBackgroundMethod.invoke(row); } catch (Throwable ignored) {}
                        } else if (sShowNoBackgroundField != null) {
                            try { showNoBg = sShowNoBackgroundField.getBoolean(row); } catch (Throwable ignored) {}
                        }
                    }
                    if (showNoBg && !isShelf) {
                        cleanupBlur(bgView);
                        param.setResult(null);
                        return;
                    }

                    int bgTop = 0;
                    if (sBackgroundTopField != null) {
                        try { bgTop = sBackgroundTopField.getInt(bgView); } catch (Throwable ignored) {}
                    }
                    int actualWidth = 0;
                    if (sActualWidthField != null) {
                        try { actualWidth = sActualWidthField.getInt(bgView); } catch (Throwable ignored) {}
                    }
                    if (isShelf && (actualWidth <= 0 || actualWidth >= bgView.getWidth()) && row != null) {
                        if (sShelfActualWidthField != null) {
                            try {
                                float sw = sShelfActualWidthField.getFloat(row);
                                if (sw > 0f && sw < bgView.getWidth()) {
                                    actualWidth = (int) sw;
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (actualWidth <= 0 || actualWidth >= bgView.getWidth()) {
                            try {
                                Object shelfIcons = XposedHelpers.getObjectField(row, "mShelfIcons");
                                if (shelfIcons != null) {
                                    int iw = (Integer) XposedHelpers.callMethod(shelfIcons, "getActualWidth");
                                    if (iw > 0 && iw < bgView.getWidth()) actualWidth = iw;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (actualWidth <= 0) actualWidth = bgView.getWidth();

                    int actualHeight = 0;
                    if (sActualHeightField != null) {
                        try { actualHeight = sActualHeightField.getInt(bgView); } catch (Throwable ignored) {}
                    }
                    if (row != null) {
                        try {
                            int rh = (sGetActualHeightMethod != null) ? (Integer) sGetActualHeightMethod.invoke(row)
                                    : (Integer) XposedHelpers.callMethod(row, "getActualHeight");
                            boolean animatingAway = false;
                            if (sIsHeadsUpAnimatingAwayMethod != null) {
                                try { animatingAway = (Boolean) sIsHeadsUpAnimatingAwayMethod.invoke(row); } catch (Throwable ignored) {}
                            }
                            if (!animatingAway && rh > actualHeight) {
                                actualHeight = rh;
                            } else if (actualHeight <= 0) {
                                actualHeight = rh;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (actualHeight <= 0) actualHeight = bgView.getHeight();

                    int clipTop = 0;
                    if (sClipTopAmountField != null) {
                        try { clipTop = sClipTopAmountField.getInt(bgView); } catch (Throwable ignored) {}
                    }
                    int clipBottom = 0;
                    if (sClipBottomAmountField != null) {
                        try { clipBottom = sClipBottomAmountField.getInt(bgView); } catch (Throwable ignored) {}
                    }

                    boolean alignedRight = false;
                    if (isShelf) {
                        try {
                            alignedRight = (Boolean) XposedHelpers.callMethod(bgView, "isAlignedToRight");
                        } catch (Throwable ignored) {}
                    }

                    int drawLeft = alignedRight ? (bgView.getWidth() - actualWidth) : 0;
                    int drawRight = alignedRight ? bgView.getWidth() : actualWidth;
                    int drawTop = Math.max(bgTop, clipTop);
                    int drawBottom = clipBottom > 0 ? (actualHeight - clipBottom) : actualHeight;

                    if (drawRight <= drawLeft || drawBottom <= drawTop) {
                        cleanupBlur(bgView);
                        param.setResult(null);
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
                    } else if (row != null) {
                        if (sGetTopCornerRadiusMethod != null && sGetBottomCornerRadiusMethod != null) {
                            try {
                                Float tr = (Float) sGetTopCornerRadiusMethod.invoke(row);
                                Float br = (Float) sGetBottomCornerRadiusMethod.invoke(row);
                                if (tr != null && br != null && (tr > 0f || br > 0f)) {
                                    topRadius = tr;
                                    bottomRadius = br;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }

                    float maxRadius = (drawBottom - drawTop) / 2.0f;
                    if (topRadius > maxRadius) topRadius = maxRadius;
                    if (bottomRadius > maxRadius) bottomRadius = maxRadius;

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

                    if (isTarget) {
                        // View 1: Frosted Glass Blur Mode (Lockscreen & Shelf Collage & Floating HUN)
                        // 1. Hardware RenderNode Wallpaper Blur (SurfaceFlinger)
                        Object blurDrawable = null;
                        if (sBackgroundBlurDrawableField != null) {
                            try { blurDrawable = sBackgroundBlurDrawableField.get(bgView); } catch (Throwable ignored) {}
                        }

                        if (blurDrawable == null) {
                            try {
                                Object vri = (sGetViewRootImplMethod != null) ? sGetViewRootImplMethod.invoke(bgView)
                                        : XposedHelpers.callMethod(bgView, "getViewRootImpl");
                                if (vri != null) {
                                    blurDrawable = (sCreateBackgroundBlurDrawableMethod != null)
                                            ? sCreateBackgroundBlurDrawableMethod.invoke(vri)
                                            : XposedHelpers.callMethod(vri, "createBackgroundBlurDrawable");
                                    if (blurDrawable instanceof Drawable) {
                                        ((Drawable) blurDrawable).setCallback(bgView);
                                    }
                                    if (sBackgroundBlurDrawableField != null) {
                                        sBackgroundBlurDrawableField.set(bgView, blurDrawable);
                                    } else {
                                        XposedHelpers.setObjectField(bgView, "mBackgroundBlurDrawable", blurDrawable);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }

                        if (blurDrawable != null) {
                            try {
                                ((Drawable) blurDrawable).setVisible(true, false);
                            } catch (Throwable ignored) {}

                            if (sSetColorMethod != null) {
                                try { sSetColorMethod.invoke(blurDrawable, 0); } catch (Throwable ignored) {}
                            } else {
                                try { XposedHelpers.callMethod(blurDrawable, "setColor", 0); } catch (Throwable ignored) {}
                            }

                            int dynamicRadius = Math.max(1, Math.round(160 * combinedAlpha));
                            if (sSetBlurRadiusMethod != null) {
                                try { sSetBlurRadiusMethod.invoke(blurDrawable, dynamicRadius); } catch (Throwable ignored) {}
                            } else {
                                try { XposedHelpers.callMethod(blurDrawable, "setBlurRadius", dynamicRadius); } catch (Throwable ignored) {}
                            }
                            try {
                                ((Drawable) blurDrawable).setAlpha(Math.round(255 * combinedAlpha));
                            } catch (Throwable ignored) {}

                            if (sSetCornerRadiusMethod != null) {
                                try { sSetCornerRadiusMethod.invoke(blurDrawable, topRadius, topRadius, bottomRadius, bottomRadius); } catch (Throwable ignored) {}
                            } else if (sSetCornerRadiusSingleMethod != null) {
                                try { sSetCornerRadiusSingleMethod.invoke(blurDrawable, Math.max(topRadius, bottomRadius)); } catch (Throwable ignored) {}
                            }
                            Drawable bd = (Drawable) blurDrawable;
                            bd.setBounds(drawLeft, drawTop, drawRight, drawBottom);
                            bd.draw(canvas);
                        }

                        // 2. Frosted Glass Tint & Specular Border Highlight (Dynamic Dark & Light Theme)
                        int frostedColor = getFrostedBackdropColor(ctx, isDark);
                        if (isShelf) {
                            frostedColor = isDark ? 0x38000000 : 0x40FFFFFF;
                        }
                        int baseTintAlpha = Color.alpha(frostedColor);
                        int animTintAlpha = Math.round(baseTintAlpha * combinedAlpha);
                        glassTintPaint.setColor((frostedColor & 0x00FFFFFF) | (animTintAlpha << 24));

                        int baseBorderAlpha = isDark ? (isShelf ? 0x35 : 0x28) : 0x45;
                        int animBorderAlpha = Math.round(baseBorderAlpha * combinedAlpha);
                        glassBorderPaint.setColor((0x00FFFFFF) | (animBorderAlpha << 24));
                        glassBorderPaint.setStrokeWidth(1.0f * densityF);

                        canvas.drawPath(sClipPath, glassTintPaint);
                        canvas.restore(); // Restore clip so border stroke is not clipped!

                        canvas.drawPath(sClipPath, glassBorderPaint);
                        canvas.save(); // Save again for ripple/restore
                    } else {
                        // View 2: Classic Stock System Background Mode (Notification Shade Menu)
                        // Turn off hardware blur so SurfaceFlinger punches no hole
                        cleanupBlur(bgView);

                        // Draw authentic uniform solid system plate
                        int solidColor = resolveStockNotifColor(bgView);
                        solidBgPaint.setColor(solidColor);
                        canvas.drawPath(sClipPath, solidBgPaint);
                    }

                    // 3. Ripple effect for touch feedback on all notification cards
                    try {
                        Object bgObj = (sBackgroundField != null) ? sBackgroundField.get(bgView)
                                : XposedHelpers.getObjectField(bgView, "mBackground");
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

                    // Cancel original onDraw because we cleanly render both View 1 and View 2!
                    param.setResult(null);
                }
            };

            XC_MethodHook registerBgViewHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        sAllNotificationBackgrounds.add((View) param.thisObject);
                    }
                }
            };
            XC_MethodHook unregisterBgViewHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        View v = (View) param.thisObject;
                        cleanupBlur(v);
                        sAllNotificationBackgrounds.remove(v);
                    }
                }
            };
            try { XposedBridge.hookAllConstructors(notifBgClass, registerBgViewHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifBgClass, "onAttachedToWindow", registerBgViewHook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(notifBgClass, "onDetachedFromWindow", unregisterBgViewHook); } catch (Throwable ignored) {}

            XposedBridge.hookAllMethods(notifBgClass, "onDraw", notifDrawHook);
            try {
                Class<?> notifShelfBgClass = cl.loadClass("com.android.systemui.statusbar.notification.shelf.NotificationShelfBackgroundView");
                if (notifShelfBgClass != null && notifShelfBgClass != notifBgClass) {
                    try { XposedBridge.hookAllConstructors(notifShelfBgClass, registerBgViewHook); } catch (Throwable ignored) {}
                    try { XposedBridge.hookAllMethods(notifShelfBgClass, "onAttachedToWindow", registerBgViewHook); } catch (Throwable ignored) {}
                    try { XposedBridge.hookAllMethods(notifShelfBgClass, "onDetachedFromWindow", unregisterBgViewHook); } catch (Throwable ignored) {}
                    XposedBridge.hookAllMethods(notifShelfBgClass, "onDraw", notifDrawHook);
                }
            } catch (Throwable ignored) {}

            try {
                Class<?> rivCls = cl.loadClass("com.android.systemui.statusbar.policy.RemoteInputView");
                XposedBridge.hookAllMethods(rivCls, "setBackgroundTintColor", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View riv = (View) param.thisObject;
                        if (riv == null) return;
                        View p = (View) riv.getParent();
                        boolean isTargetParent = false;
                        while (p != null) {
                            if (p.getClass().getName().contains("ExpandableNotificationRow")) {
                                try {
                                    boolean onKg = (sStatusBarState == 1) && (sLsToShadeProgress <= 0.02f) && (sFractionToShade <= 0.02f);
                                    if (!onKg && sIsOnKeyguardMethod != null) {
                                        try { onKg = (Boolean) sIsOnKeyguardMethod.invoke(p); } catch (Throwable ignored) {}
                                    }
                                    boolean hun = false;
                                    if (sIsPinnedMethod != null) {
                                        try { hun = (Boolean) sIsPinnedMethod.invoke(p); } catch (Throwable ignored) {}
                                    }
                                    if (!hun && sIsHeadsUpMethod != null) {
                                        try { hun = (Boolean) sIsHeadsUpMethod.invoke(p); } catch (Throwable ignored) {}
                                    }
                                    if (!hun && sIsPinnedAndExpandedMethod != null) {
                                        try { hun = (Boolean) sIsPinnedAndExpandedMethod.invoke(p); } catch (Throwable ignored) {}
                                    }
                                    if (!hun && sIsHeadsUpStateMethod != null) {
                                        try { hun = (Boolean) sIsHeadsUpStateMethod.invoke(p); } catch (Throwable ignored) {}
                                    }
                                    if (onKg || hun) {
                                        isTargetParent = true;
                                    }
                                } catch (Throwable ignored) {}
                                break;
                            }
                            ViewParent vp = p.getParent();
                            p = (vp instanceof View) ? (View) vp : null;
                        }

                        if (isTargetParent) {
                            try {
                                Object bg = XposedHelpers.getObjectField(riv, "mContentBackground");
                                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                                    android.graphics.drawable.GradientDrawable gd = (android.graphics.drawable.GradientDrawable) bg;
                                    Context ctx = riv.getContext();
                                    int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                                    boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);
                                    float density = ctx.getResources().getDisplayMetrics().density;
                                    int strokeWidth = Math.max(2, Math.round(1.0f * density));

                                    int tintColor = isDark ? 0x40000000 : 0x30FFFFFF;
                                    int strokeColor = isDark ? 0x28FFFFFF : 0x45FFFFFF;

                                    gd.setColor(tintColor);
                                    gd.setStroke(strokeWidth, strokeColor);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
                Log.e(TAG, "RemoteInputView hooked for frosted glass styling!");
            } catch (Throwable t) {
                Log.e(TAG, "RemoteInputView hook check: " + t.getMessage());
            }

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

        int tintColor = getFrostedBackdropColor(ctx, isDark);
        int strokeColor = isDark ? 0x28FFFFFF : 0x35FFFFFF;
        int strokeWidth = Math.max(2, Math.round(1.0f * ctx.getResources().getDisplayMetrics().density));

        if (sIdVolumeBg == -1) {
            String pkg = ctx.getPackageName();
            Resources res = root.getResources();
            sIdExpandContainer = res.getIdentifier("volume_expand_bar_container", "id", pkg);
            sIdVolumeBg = res.getIdentifier("volume_dialog_background", "id", pkg);
            sIdRingerBg = res.getIdentifier("ringer_buttons_background", "id", pkg);
            sIdCaptions = res.getIdentifier("odi_captions", "id", pkg);
            sIdA11y = res.getIdentifier("accessibility_slider_container", "id", pkg);
            sIdAssistant = res.getIdentifier("assistant_slider_container", "id", pkg);
        }

        // 1. Remove solid opaque background plate from volume_expand_bar_container
        if (sIdExpandContainer != 0) {
            View expandContainer = root.findViewById(sIdExpandContainer);
            if (expandContainer != null && expandContainer.getBackground() != null) {
                expandContainer.setBackground(null);
            }
        }

        // 2. Style volume_dialog_background (the main volume capsule)
        if (sIdVolumeBg != 0) {
            View bgView = root.findViewById(sIdVolumeBg);
            if (bgView != null) {
                applyFrostedGlassToDrawable(bgView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        // 3. Style ringer_buttons_background (the ringer drawer capsule)
        if (sIdRingerBg != 0) {
            View ringerBg = root.findViewById(sIdRingerBg);
            if (ringerBg != null) {
                applyFrostedGlassToDrawable(ringerBg.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        // 4. Style odi_captions if present
        if (sIdCaptions != 0) {
            View captionsView = root.findViewById(sIdCaptions);
            if (captionsView != null) {
                applyFrostedGlassToDrawable(captionsView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        // 5. Style accessibility & assistant sliders if present
        if (sIdA11y != 0) {
            View a11yView = root.findViewById(sIdA11y);
            if (a11yView != null) {
                applyFrostedGlassToDrawable(a11yView.getBackground(), tintColor, strokeWidth, strokeColor);
            }
        }

        if (sIdAssistant != 0) {
            View assistantView = root.findViewById(sIdAssistant);
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

    private static void onStatusBarStateChanged(int oldState, int newState) {
        if (sStatusBarState == newState && oldState == newState) return;
        sStatusBarState = newState;
        Log.i(TAG, "onStatusBarStateChanged: " + oldState + " -> " + newState);
        if (newState == 1) {
            sLsToShadeProgress = 0f;
            sFractionToShade = 0f;
            if (sAmbientState != null) {
                if (sLStoShadeProgressField != null) {
                    try { sLStoShadeProgressField.setFloat(sAmbientState, 0f); } catch (Throwable ignored) {}
                }
                if (sFractionToShadeField != null) {
                    try { sFractionToShadeField.setFloat(sAmbientState, 0f); } catch (Throwable ignored) {}
                }
            }
        }
        if (newState == 0 || newState == 2) {
            if (!sNotificationBlursCleared) {
                sNotificationBlursCleared = true;
                clearAllNotificationBlurs();
            }
        } else if (newState == 1 && sScreenAwake && !sBouncerShowing) {
            if (sNotificationBlursCleared) {
                sNotificationBlursCleared = false;
                invalidateNotificationStack();
            }
        }
    }

    private static void onLsToShadeProgressChanged(float progress) {
        sLsToShadeProgress = progress;
        if (progress > 0.20f) {
            if (!sNotificationBlursCleared) {
                sNotificationBlursCleared = true;
                clearAllNotificationBlurs();
            }
        } else if (progress <= 0.05f && sStatusBarState == 1 && sScreenAwake && !sBouncerShowing) {
            if (sNotificationBlursCleared) {
                sNotificationBlursCleared = false;
                invalidateNotificationStack();
            }
        }
    }

    private static void onBouncerShowingChanged(boolean showing, String reason) {
        if (sBouncerShowing == showing) return;
        sBouncerShowing = showing;
        Log.e(TAG, "onBouncerShowingChanged: " + showing + " (" + reason + ")");
        Handler h = getMainHandler();
        if (showing) {
            if (h != null) {
                h.removeCallbacks(sWakeInvalidateRunnable);
            }
            clearAllNotificationBlurs();
            invalidateNotificationStack();
        } else if (sScreenAwake && sStatusBarState == 1) {
            invalidateNotificationStack();
            if (h != null) {
                h.removeCallbacks(sWakeInvalidateRunnable);
                h.postDelayed(sWakeInvalidateRunnable, 50);
                h.postDelayed(sWakeInvalidateRunnable, 150);
                h.postDelayed(sWakeInvalidateRunnable, 300);
            }
        }
    }

    private static void onBouncerShowingChanged(boolean showing) {
        onBouncerShowingChanged(showing, "unspecified");
    }

    private static final Runnable sInvalidateNotificationStackRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                synchronized (sAllNotificationBackgrounds) {
                    for (View bg : sAllNotificationBackgrounds) {
                        if (bg != null) {
                            bg.invalidate();
                            ViewParent p = bg.getParent();
                            if (p instanceof View) {
                                ((View) p).invalidate();
                            }
                        }
                    }
                }
                if (sNsslView != null) {
                    sNsslView.invalidate();
                    if (sNsslView instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) sNsslView;
                        int count = vg.getChildCount();
                        for (int i = 0; i < count; i++) {
                            View child = vg.getChildAt(i);
                            if (child != null) {
                                child.invalidate();
                                if (child instanceof ViewGroup) {
                                    ViewGroup cvg = (ViewGroup) child;
                                    for (int j = 0; j < cvg.getChildCount(); j++) {
                                        View sub = cvg.getChildAt(j);
                                        if (sub != null) sub.invalidate();
                                    }
                                }
                            }
                        }
                    }
                    View root = sNsslView.getRootView();
                    if (root != null) root.invalidate();
                }
                if (sRootWindowView != null) {
                    sRootWindowView.invalidate();
                }
            } catch (Throwable ignored) {}
        }
    };

    private static void invalidateNotificationStack() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sInvalidateNotificationStackRunnable.run();
        } else {
            Handler h = getMainHandler();
            if (h != null) {
                h.removeCallbacks(sInvalidateNotificationStackRunnable);
                h.post(sInvalidateNotificationStackRunnable);
            } else if (sNsslView != null) {
                sNsslView.removeCallbacks(sInvalidateNotificationStackRunnable);
                sNsslView.post(sInvalidateNotificationStackRunnable);
            }
        }
    }

    private static final Runnable sClearAllNotificationBlursRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (sAllNotificationBackgrounds) {
                for (View bgView : sAllNotificationBackgrounds) {
                    cleanupBlur(bgView);
                    if (bgView != null) {
                        try {
                            bgView.invalidate();
                            View root = bgView.getRootView();
                            if (root != null) root.invalidate();
                        } catch (Throwable ignored) {}
                    }
                }
            }
            if (sNsslView != null) {
                try {
                    sNsslView.invalidate();
                    View root = sNsslView.getRootView();
                    if (root != null) root.invalidate();
                } catch (Throwable ignored) {}
            }
            if (sRootWindowView != null) {
                try { sRootWindowView.invalidate(); } catch (Throwable ignored) {}
            }
        }
    };

    private static void clearAllNotificationBlurs() {
        // 1. Synchronously on current thread: immediately set blur radius to 0 and hide drawable (zero latency)
        synchronized (sAllNotificationBackgrounds) {
            for (View bgView : sAllNotificationBackgrounds) {
                cleanupBlur(bgView);
            }
        }

        // 2. Schedule UI thread execution to invalidate views and force ViewRootImpl Aggregator to commit to SurfaceFlinger
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sClearAllNotificationBlursRunnable.run();
        } else {
            Handler h = getMainHandler();
            if (h != null) {
                h.removeCallbacks(sClearAllNotificationBlursRunnable);
                h.postAtFrontOfQueue(sClearAllNotificationBlursRunnable);
            } else if (sNsslView != null) {
                sNsslView.removeCallbacks(sClearAllNotificationBlursRunnable);
                sNsslView.post(sClearAllNotificationBlursRunnable);
            }
        }
    }

    private static void handleGoingToSleep() {
        sScreenAwake = false;
        sNotificationBlursCleared = true;
        sFractionToShade = 0f;
        sLsToShadeProgress = 0f;
        Handler h = getMainHandler();
        if (h != null) {
            h.removeCallbacks(sWakeInvalidateRunnable);
        }
        clearAllNotificationBlurs();
    }

    private static final Runnable sWakeInvalidateRunnable = new Runnable() {
        @Override
        public void run() {
            if (sSysUiState != null) {
                try {
                    long flags = (sGetFlagsMethod != null) ? (Long) sGetFlagsMethod.invoke(sSysUiState) : (Long) XposedHelpers.callMethod(sSysUiState, "getFlags");
                    if ((flags & 8L) != 0) {
                        sBouncerShowing = true;
                        clearAllNotificationBlurs();
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            if (sScreenAwake && !sBouncerShowing && sStatusBarState == 1) {
                invalidateNotificationStack();
            }
        }
    };

    private static void handleWakingUp() {
        sScreenAwake = true;
        sNotificationBlursCleared = false;
        sFractionToShade = 0f;
        sLsToShadeProgress = 0f;

        if (sSysUiState != null) {
            try {
                long flags = (sGetFlagsMethod != null) ? (Long) sGetFlagsMethod.invoke(sSysUiState) : (Long) XposedHelpers.callMethod(sSysUiState, "getFlags");
                sBouncerShowing = ((flags & 8L) != 0);
            } catch (Throwable ignored) {}
        }

        if (sBouncerShowing) {
            clearAllNotificationBlurs();
            return;
        }

        invalidateNotificationStack();

        Handler h = getMainHandler();
        if (h != null) {
            h.removeCallbacks(sWakeInvalidateRunnable);
            h.postDelayed(sWakeInvalidateRunnable, 50);
            h.postDelayed(sWakeInvalidateRunnable, 150);
            h.postDelayed(sWakeInvalidateRunnable, 300);
        }
    }

    private static int getFrostedBackdropColor(Context ctx, boolean isDark) {
        if (ctx == null) return isDark ? 0x44000000 : 0x38FFFFFF;

        int currentUiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentUiMode != sLastColorCacheUiMode) {
            sCachedFrostedColorDark = 0;
            sCachedFrostedColorLight = 0;
            sLastColorCacheUiMode = currentUiMode;
        }

        if (isDark && sCachedFrostedColorDark != 0) {
            return sCachedFrostedColorDark;
        } else if (!isDark && sCachedFrostedColorLight != 0) {
            return sCachedFrostedColorLight;
        }

        int color = 0;
        if (sLucentColorUtilsInstance != null && sGetLucentColorMethod != null) {
            try {
                color = (Integer) sGetLucentColorMethod.invoke(sLucentColorUtilsInstance, ctx, "container_frosted_backdrop");
            } catch (Throwable ignored) {}
        }
        if (color == 0 || (color >>> 24) > 0x80) {
            color = isDark ? 0x44000000 : 0x38FFFFFF;
        }

        if (isDark) {
            sCachedFrostedColorDark = color;
        } else {
            sCachedFrostedColorLight = color;
        }
        return color;
    }

    private static int resolveStockNotifColor(View bgView) {
        if (bgView == null) return 0xFF303030;
        Context ctx = bgView.getContext();
        int uiMode = (ctx != null) ? (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) : Configuration.UI_MODE_NIGHT_YES;
        boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);
        return isDark ? 0xFF303030 : 0xFFFCFCFC;
    }

    private static void cleanupBlur(View bgView) {
        if (bgView == null) return;
        try {
            Object bd = null;
            if (sBackgroundBlurDrawableField != null) {
                bd = sBackgroundBlurDrawableField.get(bgView);
            } else {
                bd = XposedHelpers.getObjectField(bgView, "mBackgroundBlurDrawable");
            }
            if (bd != null) {
                if (sSetBlurRadiusMethod != null) {
                    try { sSetBlurRadiusMethod.invoke(bd, 0); } catch (Throwable ignored) {}
                } else {
                    try { XposedHelpers.callMethod(bd, "setBlurRadius", 0); } catch (Throwable ignored) {}
                }

                if (sSetColorMethod != null) {
                    try { sSetColorMethod.invoke(bd, 0); } catch (Throwable ignored) {}
                } else {
                    try { XposedHelpers.callMethod(bd, "setColor", 0); } catch (Throwable ignored) {}
                }

                try {
                    ((Drawable) bd).setAlpha(0);
                } catch (Throwable ignored) {}

                try {
                    ((Drawable) bd).setVisible(false, false);
                } catch (Throwable ignored) {}

                try {
                    Object vri = (sGetViewRootImplMethod != null) ? sGetViewRootImplMethod.invoke(bgView)
                            : XposedHelpers.callMethod(bgView, "getViewRootImpl");
                    if (vri != null) {
                        XposedHelpers.callMethod(vri, "scheduleTraversals");
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static Canvas getNativeCanvas(Object composeCanvas) {
        if (composeCanvas == null) return null;
        if (sComposeGetInternalCanvasMethod != null) {
            try {
                return (Canvas) sComposeGetInternalCanvasMethod.invoke(composeCanvas);
            } catch (Throwable ignored) {}
        }
        if (sComposeInternalCanvasField != null) {
            try {
                return (Canvas) sComposeInternalCanvasField.get(composeCanvas);
            } catch (Throwable ignored) {}
        }
        if (!sComposeCanvasLookupDone) {
            sComposeCanvasLookupDone = true;
            try {
                Method m = composeCanvas.getClass().getMethod("getInternalCanvas");
                m.setAccessible(true);
                sComposeGetInternalCanvasMethod = m;
                return (Canvas) m.invoke(composeCanvas);
            } catch (Throwable ignored) {}
            try {
                Field f = composeCanvas.getClass().getDeclaredField("_internalCanvas");
                f.setAccessible(true);
                sComposeInternalCanvasField = f;
                return (Canvas) f.get(composeCanvas);
            } catch (Throwable ignored) {}
            try {
                Field f = composeCanvas.getClass().getDeclaredField("internalCanvas");
                f.setAccessible(true);
                sComposeInternalCanvasField = f;
                return (Canvas) f.get(composeCanvas);
            } catch (Throwable ignored) {}
            for (Field f : composeCanvas.getClass().getDeclaredFields()) {
                if (Canvas.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        sComposeInternalCanvasField = f;
                        return (Canvas) f.get(composeCanvas);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }
}
