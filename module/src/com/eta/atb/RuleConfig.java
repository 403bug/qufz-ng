package com.eta.atb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rule model + loader.
 *
 * Defaults are embedded (base64 of rules/default-rules.json). If
 * /data/local/tmp/atb-rules.json exists and parses, its app entries replace
 * the defaults for the same package; other default apps are kept.
 */
final class RuleConfig {

    static final String RULES_OVERRIDE = "/data/local/tmp/atb-rules.json";
    private static final int MAX_RULES_BYTES = 1 << 20;

    static final class MethodSpec {
        final String name;
        final List<String> params;
        final boolean hasParams;
        final Object returnValue;

        MethodSpec(String name, List<String> params, boolean hasParams, Object returnValue) {
            this.name = name;
            this.params = params;
            this.hasParams = hasParams;
            this.returnValue = returnValue;
        }
    }

    static final class SilenceRule {
        final String className;
        final boolean staticVoid;
        final List<MethodSpec> methods;
        final Set<String> exclude;
        final boolean optional;

        SilenceRule(String className, boolean staticVoid, List<MethodSpec> methods,
                    Set<String> exclude, boolean optional) {
            this.className = className;
            this.staticVoid = staticVoid;
            this.methods = methods;
            this.exclude = exclude;
            this.optional = optional;
        }
    }

    static final class SinkRule {
        final String className;
        final List<MethodSpec> methods;
        final int appIdIndex;
        final int eventIndex;
        final Set<String> appIds;
        final List<String> eventPrefixes;
        final Set<String> eventExact;
        final boolean blockAll;
        final boolean optional;

        SinkRule(String className, List<MethodSpec> methods, int appIdIndex, int eventIndex,
                 Set<String> appIds, List<String> eventPrefixes, Set<String> eventExact,
                 boolean blockAll, boolean optional) {
            this.className = className;
            this.methods = methods;
            this.appIdIndex = appIdIndex;
            this.eventIndex = eventIndex;
            this.appIds = appIds;
            this.eventPrefixes = eventPrefixes;
            this.eventExact = eventExact;
            this.blockAll = blockAll;
            this.optional = optional;
        }
    }

    static final class AppRule {
        final String pkg;
        final boolean enabled;
        final List<SilenceRule> silence;
        final List<SinkRule> sinks;

        AppRule(String pkg, boolean enabled, List<SilenceRule> silence, List<SinkRule> sinks) {
            this.pkg = pkg;
            this.enabled = enabled;
            this.silence = silence;
            this.sinks = sinks;
        }
    }

    final Map<String, AppRule> apps;
    final String source;
    final String error;

    private RuleConfig(Map<String, AppRule> apps, String source, String error) {
        this.apps = apps;
        this.source = source;
        this.error = error;
    }

    AppRule forPackage(String pkg) {
        if (pkg == null) return null;
        AppRule r = apps.get(pkg);
        if (r != null) return r;
        int colon = pkg.indexOf(':');
        if (colon > 0) return apps.get(pkg.substring(0, colon));
        return null;
    }

    private static volatile RuleConfig sCached;

    static RuleConfig get() {
        RuleConfig r = sCached;
        if (r != null) return r;
        synchronized (RuleConfig.class) {
            if (sCached == null) sCached = load();
            return sCached;
        }
    }

    private static RuleConfig load() {
        RuleConfig base;
        try {
            base = parse(decodeDefaults(), "defaults", null);
        } catch (Throwable t) {
            base = new RuleConfig(Collections.<String, AppRule>emptyMap(), "empty", "defaults: " + t);
        }

        String override = readText(RULES_OVERRIDE);
        if (override == null) return base;

        try {
            RuleConfig over = parse(override, "defaults+override", null);
            Map<String, AppRule> merged = new LinkedHashMap<>(base.apps);
            merged.putAll(over.apps);
            return new RuleConfig(merged, "defaults+override", null);
        } catch (Throwable t) {
            return new RuleConfig(base.apps, "defaults", "override ignored: " + t);
        }
    }

    private static String decodeDefaults() {
        byte[] raw = android.util.Base64.decode(DefaultRules.B64, android.util.Base64.NO_WRAP);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String readText(String path) {
        try (InputStream in = new FileInputStream(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_RULES_BYTES) return null;
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static RuleConfig parse(String json, String source, String error) throws org.json.JSONException {
        Map<String, AppRule> apps = new LinkedHashMap<>();
        JSONObject root = new JSONObject(json);
        JSONObject appsObj = root.optJSONObject("apps");
        if (appsObj != null) {
            Iterator<String> it = appsObj.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject app = appsObj.optJSONObject(pkg);
                if (app == null) continue;
                apps.put(pkg, parseApp(pkg, app));
            }
        }
        return new RuleConfig(apps, source, error);
    }

    private static AppRule parseApp(String pkg, JSONObject app) {
        boolean enabled = app.optBoolean("enabled", true);

        List<SilenceRule> silence = new ArrayList<>();
        JSONArray sa = app.optJSONArray("silence");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject o = sa.optJSONObject(i);
                if (o == null) continue;
                String cls = o.optString("class", "");
                if (cls.isEmpty()) continue;
                silence.add(new SilenceRule(
                        cls,
                        o.optBoolean("staticVoid", false),
                        parseMethods(o.optJSONArray("methods")),
                        lowerSet(o.optJSONArray("exclude")),
                        o.optBoolean("optional", false)));
            }
        }

        List<SinkRule> sinks = new ArrayList<>();
        JSONArray ka = app.optJSONArray("sinks");
        if (ka != null) {
            for (int i = 0; i < ka.length(); i++) {
                JSONObject o = ka.optJSONObject(i);
                if (o == null) continue;
                String cls = o.optString("class", "");
                if (cls.isEmpty()) continue;
                List<MethodSpec> methods = parseMethods(o.optJSONArray("methods"));
                if (methods.isEmpty()) continue;
                List<String> prefixes = lowerList(o.optJSONArray("eventPrefixes"));
                Set<String> exact = lowerSet(o.optJSONArray("eventExact"));
                sinks.add(new SinkRule(
                        cls,
                        methods,
                        o.optInt("appIdIndex", 0),
                        o.optInt("eventIndex", 1),
                        stringSet(o.optJSONArray("appIds")),
                        prefixes,
                        exact,
                        o.optBoolean("blockAll", false),
                        o.optBoolean("optional", false)));
            }
        }

        return new AppRule(pkg, enabled, silence, sinks);
    }

    private static List<MethodSpec> parseMethods(JSONArray array) {
        List<MethodSpec> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            if (name.isEmpty()) continue;
            JSONArray pa = o.optJSONArray("params");
            List<String> params = pa == null ? Collections.<String>emptyList() : stringList(pa);
            Object rv = o.opt("returnValue");
            if (rv == JSONObject.NULL) rv = null;
            out.add(new MethodSpec(name, params, pa != null, rv));
        }
        return out;
    }

    private static List<String> stringList(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static List<String> lowerList(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (String s : stringList(a)) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    private static Set<String> stringSet(JSONArray a) {
        return new LinkedHashSet<>(a == null ? Collections.<String>emptyList() : stringList(a));
    }

    private static Set<String> lowerSet(JSONArray a) {
        Set<String> out = new LinkedHashSet<>();
        if (a == null) return out;
        for (String s : stringList(a)) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }
}
