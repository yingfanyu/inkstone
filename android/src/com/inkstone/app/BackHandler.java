package com.inkstone.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Toast;

/**
 * 返回键实现。
 *
 * 网页端没有做 history 路由（源码里没有任何 pushState/popstate），所以
 * WebView 的历史栈永远是空的，canGoBack() 恒为 false —— 壳原来那套
 * 「先问页面 __inkstoneBack、再 canGoBack」的判定根本走不通。
 *
 * 这里改成直接点页面自己的「返回笔记列表」按钮（移动端打开笔记时才有），
 * 没有这个按钮才进入退出确认。
 */
public final class BackHandler {

    private static final long EXIT_WINDOW_MS = 2500;
    private static final long DEBOUNCE_MS = 300;

    private static long armedAt;
    private static long lastPressAt;
    private static boolean pending;

    private static final String CLICK_BACK_JS =
            "(function(){try{"
                    + "var s='[aria-label=\"返回笔记列表\"],[aria-label=\"Back to notes\"],"
                    + "[aria-label=\"Back to note list\"],[aria-label=\"返回笔记\"],"
                    + "[data-action=\"back-to-notes\"]';"
                    + "var b=document.querySelector(s);"
                    + "if(b){b.click();return 'back';}"
                    + "return 'none';"
                    + "}catch(e){return 'none';}})()";

    private BackHandler() {
    }

    public static void handle(final Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPressAt < DEBOUNCE_MS) {
                return;
            }
            lastPressAt = now;

            View lock = activity.findViewById(idOf(activity, "lockPanel"));
            if (lock != null && lock.getVisibility() == View.VISIBLE) {
                activity.moveTaskToBack(true);
                return;
            }

            if (armedAt > 0 && now - armedAt < EXIT_WINDOW_MS) {
                armedAt = 0;
                activity.moveTaskToBack(true);
                return;
            }

            final WebView web = findWebView(activity);
            if (web == null || web.getUrl() == null) {
                arm(activity);
                return;
            }

            pending = true;
            final Handler handler = new Handler(Looper.getMainLooper());
            final Runnable fallback = new Runnable() {
                public void run() {
                    if (pending) {
                        pending = false;
                        arm(activity);
                    }
                }
            };
            handler.postDelayed(fallback, 600);

            web.evaluateJavascript(CLICK_BACK_JS, new ValueCallback<String>() {
                public void onReceiveValue(String value) {
                    if (!pending) {
                        return;
                    }
                    pending = false;
                    handler.removeCallbacks(fallback);
                    if (value != null && value.contains("back")) {
                        armedAt = 0;
                        return;
                    }
                    arm(activity);
                }
            });
        } catch (Throwable t) {
            arm(activity);
        }
    }

    private static void arm(Activity activity) {
        armedAt = SystemClock.elapsedRealtime();
        try {
            Toast.makeText(activity, "再按一次退出", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static int idOf(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", "com.inkstone.app");
    }

    private static WebView findWebView(Activity activity) {
        View root = activity.getWindow().getDecorView();
        return find(root);
    }

    private static WebView find(View root) {
        if (root instanceof WebView) {
            return (WebView) root;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            WebView found = find(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
