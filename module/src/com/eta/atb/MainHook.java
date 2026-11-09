package com.eta.atb;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;

/**
 * AntiTelemetryBlock (ATB).
 *
 * A small, rule-driven LSPosed module. Defaults target the ColorOS / OPPO
 * PhoneManager anti-fraud telemetry path; extra apps can be added by dropping
 * rules at /data/local/tmp/atb-rules.json (see rules/default-rules.json for
 * the schema).
 *
 * Package: com.eta.atb (coexists with the original com.eta.afb).
 */
public final class MainHook extends XposedModule {

    static final String TAG = "ATB";
    static final String KILL_SWITCH = "/data/local/tmp/atb.disable";

    private static volatile RuleConfig sRules;
    private static volatile SharedPreferences sPrefs;
    private static volatile boolean sDisabled;

    public MainHook() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Logger log = new Logger(this);
        log.info("module loaded, api=" + getApiVersion() + " framework=" + getFrameworkName());
        RuleConfig cfg = rules();
        log.info("rules source=" + cfg.source + (cfg.error == null ? "" : " (" + cfg.error + ")"));
        log.info("targets=" + cfg.apps.keySet());
        SharedPreferences p = remotePrefs();
        if (p != null) {
            log.info("settings phone=" + p.getBoolean(FeatureToggles.PHONE_MANAGER, true)
                    + " family=" + p.getBoolean(FeatureToggles.SECURITY_FAMILY, false)
                    + " dcs=" + p.getBoolean(FeatureToggles.DCS, false)
                    + " browser=" + p.getBoolean(FeatureToggles.BROWSER, false)
                    + " kill=" + p.getBoolean(FeatureToggles.KILL_SWITCH, false));
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null) return;

        RuleConfig cfg = rules();
        RuleConfig.AppRule app = cfg.forPackage(pkg);
        if (app == null) return; // not a configured target: stay completely passive

        Logger log = new Logger(this);
        if (!app.enabled) {
            log.info("pkg=" + pkg + " disabled by rules");
            return;
        }
        if (!FeatureToggles.enabled(pkg, remotePrefs())) {
            log.info("pkg=" + pkg + " disabled by UI settings");
            return;
        }
        sDisabled = new File(KILL_SWITCH).exists();
        if (sDisabled) {
            log.info("pkg=" + pkg + " kill switch ON (" + KILL_SWITCH + "), no-op");
            return;
        }

        log.info("attach " + pkg);
        try {
            new RuleEngine(this, param.getDefaultClassLoader(), pkg, app, log).apply();
        } catch (Throwable t) {
            log.error("engine failed for " + pkg + " : " + t);
        }
    }

    /** Read once at package load; create the file then force-stop the target. */
    static boolean isDisabled() {
        return sDisabled;
    }

    private SharedPreferences remotePrefs() {
        SharedPreferences p = sPrefs;
        if (p != null) return p;
        try {
            p = getRemotePreferences(FeatureToggles.PREFS);
            sPrefs = p;
        } catch (Throwable t) {
            // fall back to built-in defaults
        }
        return p;
    }

    private static RuleConfig rules() {
        RuleConfig r = sRules;
        if (r != null) return r;
        synchronized (MainHook.class) {
            if (sRules == null) sRules = RuleConfig.get();
            return sRules;
        }
    }
}
