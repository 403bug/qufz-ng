package com.eta.atb;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Simple switch + scope panel.
 *
 * Feature switches are written through the libxposed remote-preferences
 * service; the injected module reads the same group via
 * XposedInterface.getRemotePreferences("atb_config").
 *
 * Scope status is read with XposedService.getScope(); missing targets can be
 * requested with XposedService.requestScope().
 *
 * Changes take effect when the target process is restarted (force-stop).
 */
public final class SettingsActivity extends Activity {

    private static final String[] SCOPE_PACKAGES = {
            "com.coloros.phonemanager",
            "com.coloros.securityguard",
            "com.coloros.familyguard",
            "com.oplus.statistics.rom",
            "com.heytap.browser",
    };
    private static final String[] SCOPE_LABELS = {
            "手机管家",
            "安全事件",
            "家人守护",
            "系统 DCS",
            "浏览器",
    };

    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private XposedService xposedService;
    private TextView status;
    private TextView scopeStatus;
    private Button refreshScopeButton;
    private Button scopeRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("ATB 遥测阻断");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setText("正在连接 LSPosed 服务…");
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        TextView intro = new TextView(this);
        intro.setText("选择要拦截的链路。修改后请强停对应 App 再重新打开。");
        intro.setTextSize(14);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        addSwitch(root, "手机管家：AI 反诈上报（默认开）", FeatureToggles.PHONE_MANAGER, true);
        addSwitch(root, "安全事件 → 家人守护：安全事件同步（默认关）", FeatureToggles.SECURITY_FAMILY, false);
        addSwitch(root, "系统 DCS：OPPO 通用遥测上传（实验性，默认关）", FeatureToggles.DCS, false);
        addSwitch(root, "浏览器：网页反诈上报（默认关）", FeatureToggles.BROWSER, false);
        addSwitch(root, "全部停用（本次注入空转）", FeatureToggles.KILL_SWITCH, false);

        TextView scopeTitle = new TextView(this);
        scopeTitle.setText("作用域");
        scopeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        scopeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        scopeTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(scopeTitle);

        TextView scopeHint = new TextView(this);
        scopeHint.setText("DCS / 浏览器要生效，必须先在框架作用域内。");
        scopeHint.setTextSize(13);
        scopeHint.setPadding(0, 0, 0, dp(8));
        root.addView(scopeHint);

        scopeStatus = new TextView(this);
        scopeStatus.setTextSize(13);
        scopeStatus.setPadding(0, 0, 0, dp(8));
        root.addView(scopeStatus);

        refreshScopeButton = new Button(this);
        refreshScopeButton.setText("刷新作用域");
        refreshScopeButton.setEnabled(false);
        refreshScopeButton.setOnClickListener(v -> refreshScope());
        root.addView(refreshScopeButton);

        scopeRequest = new Button(this);
        scopeRequest.setText("把缺失目标加入作用域");
        scopeRequest.setEnabled(false);
        scopeRequest.setOnClickListener(v -> requestMissingScope());
        root.addView(scopeRequest);

        TextView note = new TextView(this);
        note.setText("改完设置后执行：\n"
                + "adb shell am force-stop com.coloros.phonemanager\n"
                + "adb shell am force-stop com.coloros.securityguard\n"
                + "adb shell am force-stop com.coloros.familyguard\n"
                + "adb shell am force-stop com.oplus.statistics.rom\n"
                + "adb shell am force-stop com.heytap.browser");
        note.setTextSize(13);
        note.setPadding(0, dp(24), 0, dp(8));
        root.addView(note);

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                runOnUiThread(() -> attach(service));
            }

            @Override
            public void onServiceDied(XposedService service) {
                runOnUiThread(() -> detach());
            }
        });

        setContentView(scroll);
    }

    private void attach(XposedService service) {
        xposedService = service;
        try {
            prefs = service.getRemotePreferences(FeatureToggles.PREFS);
        } catch (Throwable t) {
            status.setText("LSPosed 服务不支持 remote preferences：" + t);
            return;
        }
        for (Map.Entry<String, Switch> entry : switches.entrySet()) {
            final String key = entry.getKey();
            final Switch sw = entry.getValue();
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(prefs.getBoolean(key, defaults.get(key)));
            sw.setEnabled(true);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    boolean ok = prefs.edit().putBoolean(key, isChecked).commit();
                    Toast.makeText(this, ok ? "已保存；强停目标 App 后生效" : "保存失败",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(this, "保存失败：" + t, Toast.LENGTH_SHORT).show();
                }
            });
        }
        status.setText("LSPosed 服务已连接；改完请强停对应 App");
        refreshScopeButton.setEnabled(true);
        refreshScope();
    }

    private void detach() {
        xposedService = null;
        prefs = null;
        for (Switch sw : switches.values()) {
            sw.setEnabled(false);
        }
        status.setText("LSPosed 服务已断开；请确认框架已启用");
        scopeStatus.setText("作用域：未连接");
        refreshScopeButton.setEnabled(false);
        scopeRequest.setEnabled(false);
    }

    private void refreshScope() {
        if (xposedService == null) return;
        try {
            List<String> scope = xposedService.getScope();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < SCOPE_PACKAGES.length; i++) {
                boolean in = scope != null && scope.contains(SCOPE_PACKAGES[i]);
                sb.append(in ? "✓ " : "✗ ")
                        .append(SCOPE_LABELS[i]).append("  ")
                        .append(SCOPE_PACKAGES[i]).append('\n');
            }
            scopeStatus.setText(sb.toString().trim());
            scopeRequest.setEnabled(true);
        } catch (Throwable t) {
            scopeStatus.setText("读取作用域失败：" + t);
            scopeRequest.setEnabled(false);
        }
    }

    private void requestMissingScope() {
        if (xposedService == null) return;
        try {
            List<String> scope = xposedService.getScope();
            List<String> missing = new ArrayList<>();
            for (String p : SCOPE_PACKAGES) {
                if (scope == null || !scope.contains(p)) missing.add(p);
            }
            if (missing.isEmpty()) {
                Toast.makeText(this, "5 个目标都已在作用域", Toast.LENGTH_SHORT).show();
                return;
            }
            scopeRequest.setEnabled(false);
            xposedService.requestScope(missing, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    runOnUiThread(() -> {
                        Toast.makeText(SettingsActivity.this,
                                "已加入作用域：" + approved, Toast.LENGTH_LONG).show();
                        refreshScope();
                    });
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    runOnUiThread(() -> {
                        scopeRequest.setEnabled(true);
                        Toast.makeText(SettingsActivity.this,
                                "请求失败：" + message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Throwable t) {
            scopeRequest.setEnabled(true);
            Toast.makeText(this, "请求作用域失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private Switch addSwitch(LinearLayout parent, String label, final String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setChecked(def);
        sw.setEnabled(false);
        switches.put(key, sw);
        defaults.put(key, def);
        parent.addView(sw);
        return sw;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}