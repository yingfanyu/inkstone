package com.inkstone.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

/**
 * 独立的设置页：显示当前解析结果，允许在特殊情况下手工覆盖域名与端口。
 * 修改域名/端口需要「两次确认 + 指纹（或输入确认词）」。
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFFF6F3EE;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1E1B18;
    private static final int MUTED = 0xFF7A7168;
    private static final int ACCENT = 0xFFB5482E;
    private static final int OK = 0xFF2E7D5B;
    private static final Pattern HOST_RE = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?$");

    private TextView tvUrl;
    private TextView tvCookie;
    private TextView tvHost;
    private TextView tvHostState;
    private TextView tvPort;
    private TextView tvPortState;
    private TextView tvRaw;
    private TextView tvTime;
    private TextView tvPortOverride;
    private TextView tvBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Endpoint.init(getApplicationContext());
        setContentView(buildUi());
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("连接设置");
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Inkstone 客户端 · nas-txt 构建 1.5\n地址端口由 DNS TXT 每日解析，日常不需要改动。");
        sub.setTextColor(MUTED);
        sub.setTextSize(12);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        // 当前地址
        LinearLayout card1 = card(root, "当前访问地址");
        tvUrl = valueLine(card1, "", true);
        tvCookie = hintLine(card1);
        tvBusy = new TextView(this);
        tvBusy.setTextColor(ACCENT);
        tvBusy.setTextSize(11);
        tvBusy.setPadding(0, dp(6), 0, 0);
        tvBusy.setVisibility(View.GONE);
        card1.addView(tvBusy);

        // 域名
        LinearLayout card2 = card(root, "域名");
        tvHost = valueLine(card2, "", true);
        tvHostState = hintLine(card2);
        buttonRow(card2,
                action("覆盖域名", new View.OnClickListener() {
                    public void onClick(View v) {
                        promptHost();
                    }
                }),
                action("恢复默认", new View.OnClickListener() {
                    public void onClick(View v) {
                        resetHost();
                    }
                }));

        // TXT 端口
        LinearLayout card3 = card(root, "TXT 端口");
        hintText(card3, "记录：" + Endpoint.TXT_NAME + "（只在解析当天有效，次日自动重新获取）");
        tvPort = valueLine(card3, "", true);
        tvTime = hintLine(card3);
        tvPortState = hintLine(card3);
        tvRaw = hintLine(card3);
        tvPortOverride = hintLine(card3);
        buttonRow(card3,
                action("立即重新解析", new View.OnClickListener() {
                    public void onClick(View v) {
                        reResolve();
                    }
                }),
                action("覆盖端口", new View.OnClickListener() {
                    public void onClick(View v) {
                        promptPort();
                    }
                }));

        // 说明
        LinearLayout card4 = card(root, "说明");
        hintText(card4, "· 打开本页：长按桌面上的 Inkstone 图标选「连接设置」，或在本应用内三指轻点；连不上时长按错误面板也可进入。");
        hintText(card4, "· 域名覆盖后会一直生效，直到点「恢复默认」。");
        hintText(card4, "· TXT 端口与手工覆盖的端口都只在当天有效，跨天自动重新从 TXT 获取。");
        hintText(card4, "· 任一覆盖都需要两次确认 + 指纹验证；没有指纹时需输入「确认覆盖」。");
        hintText(card4, "· 改完回到 Inkstone 若打不开，点页面上的「重试」即可用新地址重新加载。");

        Button relaunch = new Button(this);
        relaunch.setText("重新打开 Inkstone");
        relaunch.setAllCaps(false);
        relaunch.setTextColor(0xFFFFFFFF);
        relaunch.setBackground(pill(ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(20);
        relaunch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setClassName(getPackageName(), "com.inkstone.app.MainActivity");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }
        });
        root.addView(relaunch, lp);

        return scroll;
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(card, lp);

        TextView head = new TextView(this);
        head.setText(heading);
        head.setTextColor(ACCENT);
        head.setTextSize(12);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(0, 0, 0, dp(8));
        card.addView(head);
        return card;
    }

    private TextView valueLine(LinearLayout card, String value, boolean mono) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(TEXT);
        tv.setTextSize(15);
        if (mono) {
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        tv.setTextIsSelectable(true);
        card.addView(tv);
        return tv;
    }

    private TextView hintLine(LinearLayout card) {
        TextView tv = new TextView(this);
        tv.setTextColor(MUTED);
        tv.setTextSize(11);
        tv.setPadding(0, dp(6), 0, 0);
        card.addView(tv);
        return tv;
    }

    private void hintText(LinearLayout card, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(MUTED);
        tv.setTextSize(11);
        tv.setPadding(0, dp(4), 0, 0);
        tv.setLineSpacing(dp(2), 1f);
        card.addView(tv);
    }

    private void buttonRow(LinearLayout card, View... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        for (View b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            row.addView(b, lp);
        }
        card.addView(row);
    }

    private Button action(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(ACCENT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14B5482E);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(listener);
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        return bg;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- 状态刷新

    private void refreshUi() {
        if (tvUrl == null) {
            return;
        }
        tvUrl.setText(Endpoint.url());
        updateCookieState();
        tvHost.setText(Endpoint.host());

        String override = Endpoint.hostOverride();
        String txtHost = Endpoint.txtHost();
        if (override != null && override.length() > 0) {
            tvHostState.setText("已手工覆盖（默认：" + Endpoint.DEFAULT_HOST + "）");
            tvHostState.setTextColor(ACCENT);
        } else if (txtHost != null && txtHost.length() > 0) {
            tvHostState.setText("来自 TXT 记录指定（" + Endpoint.TXT_NAME + "）");
            tvHostState.setTextColor(OK);
        } else {
            tvHostState.setText("内置默认值（TXT 里未指定主机，只给了端口）");
            tvHostState.setTextColor(MUTED);
        }

        int port = Endpoint.port();
        tvPort.setText(port > 0 ? String.valueOf(port) : "未解析到");

        String txtPort = Endpoint.txtPort();
        boolean fresh = Endpoint.txtFresh();
        if (txtPort.length() == 0) {
            tvPortState.setText("尚无不含日期的解析结果");
            tvPortState.setTextColor(MUTED);
        } else if (fresh) {
            tvPortState.setText("TXT 解析值 " + txtPort + " · 今天有效");
            tvPortState.setTextColor(OK);
        } else {
            tvPortState.setText("TXT 解析值 " + txtPort + " · 已于 " + Endpoint.txtDate() + " 过期，正在等待重新解析");
            tvPortState.setTextColor(ACCENT);
        }

        long at = Endpoint.txtAt();
        tvTime.setText("最近解析：" + Endpoint.formatTime(at));

        String raw = Endpoint.txtRaw();
        tvRaw.setText("TXT 原始值：" + (raw.length() > 0 ? raw : "—"));

        int po = Endpoint.portOverride();
        if (po > 0) {
            tvPortOverride.setText("手工覆盖端口 " + po + "（今天有效）");
            tvPortOverride.setTextColor(ACCENT);
        } else {
            tvPortOverride.setText("未手工覆盖端口");
            tvPortOverride.setTextColor(MUTED);
        }
    }

    /** 显示当前地址下是否还留着会话 Cookie，方便判断是"没存住"还是"服务端踢了"。 */
    private void updateCookieState() {
        String cookie = "";
        try {
            cookie = CookieManager.getInstance().getCookie(Endpoint.url());
        } catch (Throwable ignored) {
        }
        boolean hasSession = cookie != null && cookie.contains("inkstone_session");
        if (hasSession) {
            tvCookie.setText("登录态：已保存 Cookie（无需重复登录）");
            tvCookie.setTextColor(OK);
        } else {
            tvCookie.setText("登录态：当前地址下没有会话 Cookie——下次打开需要重新登录");
            tvCookie.setTextColor(ACCENT);
        }
    }

    private void setBusy(boolean busy, String text) {
        tvBusy.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (text != null) {
            tvBusy.setText(text);
        }
    }

    // ----------------------------------------------------------------- 动作

    private void reResolve() {
        setBusy(true, "正在查询 TXT…");
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = Endpoint.resolveNow(4000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        setBusy(false, null);
                        refreshUi();
                        toast(ok ? "解析成功：" + Endpoint.port() : "解析失败，已保留原值");
                    }
                });
            }
        }, "inkstone-resolve").start();
    }

    private void promptHost() {
        final EditText input = new EditText(this);
        input.setText(Endpoint.host());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        new AlertDialog.Builder(this)
                .setTitle("覆盖访问域名")
                .setMessage("留空或用「恢复默认」可回到 " + Endpoint.DEFAULT_HOST + "。\n域名必须能解析，且证书要覆盖它，否则应用打不开。")
                .setView(input)
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String value = input.getText().toString().trim();
                        if (value.length() > 0 && !HOST_RE.matcher(value).matches()) {
                            toast("域名格式不正确");
                            return;
                        }
                        confirmApply("域名", Endpoint.host(), value.length() == 0 ? Endpoint.DEFAULT_HOST : value,
                                new Runnable() {
                                    public void run() {
                                        Endpoint.setHostOverride(value);
                                        refreshUi();
                                        toast("域名已覆盖：" + Endpoint.host());
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptPort() {
        final EditText input = new EditText(this);
        int po = Endpoint.portOverride();
        input.setText(po > 0 ? String.valueOf(po) : (Endpoint.port() > 0 ? String.valueOf(Endpoint.port()) : ""));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        new AlertDialog.Builder(this)
                .setTitle("手工覆盖端口")
                .setMessage("端口只在今天有效，明天会自动回到 TXT 解析结果。")
                .setView(input)
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int value;
                        try {
                            value = Integer.parseInt(input.getText().toString().trim());
                        } catch (Throwable t) {
                            toast("请输入 1-65535 的端口号");
                            return;
                        }
                        if (value <= 0 || value > 65535) {
                            toast("请输入 1-65535 的端口号");
                            return;
                        }
                        confirmApply("端口", String.valueOf(Endpoint.port()), String.valueOf(value),
                                new Runnable() {
                                    public void run() {
                                        Endpoint.setPortOverride(value);
                                        refreshUi();
                                        toast("端口已覆盖为 " + value);
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetHost() {
        if (Endpoint.hostOverride() == null) {
            toast("当前就是默认域名");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复默认域名？")
                .setMessage("将回到 " + Endpoint.DEFAULT_HOST + "，覆盖的域名会被清除。")
                .setPositiveButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Endpoint.clearHostOverride();
                        refreshUi();
                        toast("已恢复默认域名");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ----------------------------------------------------- 二次确认 + 指纹

    private void confirmApply(final String what, final String oldValue, final String newValue,
                              final Runnable onApplied) {
        new AlertDialog.Builder(this)
                .setTitle("确认修改" + what + "？")
                .setMessage("这是一项会影响连接的操作。\n\n" +
                        "原值：" + oldValue + "\n" +
                        "新值：" + newValue + "\n\n" +
                        "改错会导致应用无法打开，需要重新登录。")
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        secondConfirm(what, newValue, onApplied);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void secondConfirm(final String what, final String newValue, final Runnable onApplied) {
        new AlertDialog.Builder(this)
                .setTitle("再次确认")
                .setMessage("确定要把" + what + "改成下面这个值吗？\n\n" + newValue + "\n\n下一步需要指纹验证。")
                .setPositiveButton("确认修改", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        requireAuth(onApplied);
                    }
                })
                .setNegativeButton("我再想想", null)
                .show();
    }

    private void requireAuth(final Runnable onSuccess) {
        if (Build.VERSION.SDK_INT >= 28) {
            boolean shown = BiometricAuth.run(this, new BiometricAuth.Gate() {
                public void pass() {
                    onSuccess.run();
                }

                public void unavailable() {
                    typeToConfirm(onSuccess);
                }

                public void cancelled() {
                    toast("已取消，未做任何修改");
                }
            });
            if (shown) {
                return;
            }
        }
        typeToConfirm(onSuccess);
    }

    /**
     * BiometricPrompt 只有 API 28+ 才有，单独放一个类里，
     * 免得低版本设备加载 SettingsActivity 时碰到不存在的类。
     */
    private static final class BiometricAuth {

        interface Gate {
            void pass();

            void unavailable();

            void cancelled();
        }

        static boolean run(final Activity activity, final Gate gate) {
            try {
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("安全验证")
                        .setSubtitle("验证指纹后才会应用修改")
                        .setNegativeButton("取消", activity.getMainExecutor(),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        gate.cancelled();
                                    }
                                })
                        .build();
                prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                gate.pass();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                // 5/10/13 = 取消 / 用户取消 / 负按钮
                                if (code == 5 || code == 10 || code == 13) {
                                    gate.cancelled();
                                    return;
                                }
                                gate.unavailable();
                            }
                        });
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private void typeToConfirm(final Runnable onSuccess) {
        final EditText input = new EditText(this);
        input.setHint("确认覆盖");
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        new AlertDialog.Builder(this)
                .setTitle("设备验证不可用")
                .setMessage("没有可用的指纹验证。请输入「确认覆盖」四个字继续。")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (TextUtils.equals("确认覆盖", input.getText().toString().trim())) {
                            onSuccess.run();
                        } else {
                            toast("输入不匹配，已取消");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
