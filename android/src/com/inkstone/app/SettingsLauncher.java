package com.inkstone.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * 设置页入口：不再单独占一个桌面图标。
 *  1) 长按桌面上的 Inkstone 图标 -> 「连接设置」（静态快捷方式）
 *  2) App 内三指轻点页面任意位置
 *  3) 连不上时的错误面板，长按空白处
 */
public final class SettingsLauncher {

    private static final String HINT_FLAG = "settings_hint_shown_v1";
    private static final String TARGET = "com.inkstone.app.SettingsActivity";
    private static WeakReference<View> hookedWeb = new WeakReference<View>(null);

    private SettingsLauncher() {
    }

    public static void register(Context context) {
        if (!(context instanceof Application)) {
            return;
        }
        try {
            ((Application) context).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        }

                        public void onActivityStarted(Activity activity) {
                        }

                        public void onActivityResumed(Activity activity) {
                            if (activity != null && "com.inkstone.app.MainActivity"
                                    .equals(activity.getClass().getName())) {
                                install(activity);
                            }
                        }

                        public void onActivityPaused(Activity activity) {
                            flushCookies();
                        }

                        public void onActivityStopped(Activity activity) {
                            flushCookies();
                        }

                        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                        }

                        public void onActivityDestroyed(Activity activity) {
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** WebView 的 Cookie 是异步落盘的，不 flush 的话进程被杀就会丢会话。 */
    static void flushCookies() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
    }

    private static void install(final Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            WebView web = findWebView(decor);
            if (web != null && hookedWeb.get() != web) {
                web.setOnTouchListener(new ThreeFinger(activity));
                hookedWeb = new WeakReference<View>(web);
            }
            if (decor != null) {
                View errorPanel = decor.findViewById(idOf(activity, "errorPanel"));
                if (errorPanel != null && errorPanel.getTag() == null) {
                    errorPanel.setTag("hooked");
                    errorPanel.setOnLongClickListener(new View.OnLongClickListener() {
                        public boolean onLongClick(View v) {
                            open(activity);
                            return true;
                        }
                    });
                    errorPanel.setOnClickListener(null);
                }
            }
            maybeShowHint(activity);
        } catch (Throwable ignored) {
        }
    }

    private static void maybeShowHint(Activity activity) {
        if (activity.getSharedPreferences("inkstone_endpoint", Context.MODE_PRIVATE)
                .getBoolean(HINT_FLAG, false)) {
            return;
        }
        activity.getSharedPreferences("inkstone_endpoint", Context.MODE_PRIVATE)
                .edit().putBoolean(HINT_FLAG, true).apply();
        Toast.makeText(activity, "小提示：三指轻点页面，或长按桌面图标选「连接设置」", Toast.LENGTH_LONG).show();
    }

    private static int idOf(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", "com.inkstone.app");
    }

    private static WebView findWebView(View root) {
        if (root instanceof WebView) {
            return (WebView) root;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            WebView found = findWebView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static void open(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), TARGET);
            activity.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 三指轻点：只观察，不消费事件，页面滑动/选字完全不受影响。 */
    private static final class ThreeFinger implements View.OnTouchListener {

        private final Activity activity;
        private int maxPointers;
        private long downAt;
        private boolean moved;
        private float startX;
        private float startY;

        ThreeFinger(Activity activity) {
            this.activity = activity;
        }

        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    maxPointers = 1;
                    moved = false;
                    downAt = System.currentTimeMillis();
                    startX = event.getX();
                    startY = event.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPointers = Math.max(maxPointers, event.getPointerCount());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() > 0) {
                        float dx = Math.abs(event.getX() - startX);
                        float dy = Math.abs(event.getY() - startY);
                        if (dx > 40 * v.getResources().getDisplayMetrics().density
                                || dy > 40 * v.getResources().getDisplayMetrics().density) {
                            moved = true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (maxPointers >= 3 && !moved
                            && System.currentTimeMillis() - downAt < 600) {
                        open(activity);
                        maxPointers = 0;
                        return true;
                    }
                    maxPointers = 0;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    maxPointers = 0;
                    break;
                default:
                    break;
            }
            return false;
        }
    }
}
