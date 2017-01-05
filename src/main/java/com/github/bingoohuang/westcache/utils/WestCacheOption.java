package com.github.bingoohuang.westcache.utils;

import com.github.bingoohuang.westcache.WestCacheRegistry;
import com.github.bingoohuang.westcache.WestCacheable;
import com.github.bingoohuang.westcache.base.*;
import com.google.common.collect.Maps;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;
import lombok.val;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * @author bingoohuang [bingoohuang@gmail.com] Created on 2016/12/22.
 */
@Value @AllArgsConstructor
public class WestCacheOption {
    @Getter private final WestCacheFlusher flusher;
    @Getter private final WestCacheManager manager;
    @Getter private final WestCacheSnapshot snapshot;
    @Getter private final WestCacheConfig config;
    @Getter private final WestCacheInterceptor interceptor;
    @Getter private final WestCacheKeyer keyer;
    @Getter private final String key;
    @Getter private final Map<String, String> specs;
    @Getter private final Method method;

    public static Builder newBuilder() {
        return new Builder();
    }


    public static class Builder {
        WestCacheFlusher flusher = WestCacheRegistry.getFlusher("");
        WestCacheManager manager = WestCacheRegistry.getManager("");
        WestCacheSnapshot snapshot = WestCacheRegistry.getSnapshot("");
        WestCacheConfig config = WestCacheRegistry.getConfig("");
        WestCacheInterceptor interceptor = WestCacheRegistry.getInterceptor("");
        WestCacheKeyer keyer = WestCacheRegistry.getKeyer("");
        String key = "";
        Map<String, String> specs = Maps.newHashMap();
        Method method;

        public Builder flusher(String flusherName) {
            this.flusher = WestCacheRegistry.getFlusher(flusherName);
            return this;
        }

        public Builder manager(String managerName) {
            this.manager = WestCacheRegistry.getManager(managerName);
            return this;
        }

        public Builder snapshot(String snapshotName) {
            this.snapshot = WestCacheRegistry.getSnapshot(snapshotName);
            return this;
        }

        public Builder config(String configName) {
            this.config = WestCacheRegistry.getConfig(configName);
            return this;
        }

        public Builder interceptor(String interceptorName) {
            this.interceptor = WestCacheRegistry.getInterceptor(interceptorName);
            return this;
        }

        public Builder keyer(String keyerName) {
            this.keyer = WestCacheRegistry.getKeyer(keyerName);
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder specs(String specs) {
            this.specs = Specs.parseSpecs(specs);
            return this;
        }

        public Builder specs(Map<String, String> specs) {
            this.specs = specs;
            return this;
        }

        public Builder method(Method method) {
            this.method = method;
            return this;
        }

        public WestCacheOption build() {
            return new WestCacheOption(flusher, manager, snapshot,
                    config, interceptor, keyer, key, specs, method);
        }

        public WestCacheOption build(WestCacheable westCacheable, Method method) {
            this.flusher = WestCacheRegistry.getFlusher(westCacheable.flusher());
            this.manager = WestCacheRegistry.getManager(westCacheable.manager());
            this.snapshot = WestCacheRegistry.getSnapshot(westCacheable.snapshot());
            this.config = WestCacheRegistry.getConfig(westCacheable.config());
            this.interceptor = WestCacheRegistry.getInterceptor(westCacheable.interceptor());
            this.keyer = WestCacheRegistry.getKeyer(westCacheable.keyer());
            this.key = westCacheable.key();
            this.specs = Specs.parseSpecs(westCacheable.specs());
            this.method = method;
            return build();
        }
    }

    public static WestCacheOption parseWestCacheable(Method m) {
        Map<String, String> attrs = Anns.parseWestCacheable(m, WestCacheable.class);
        return attrs == null ? null : buildOption(attrs, m);
    }

    private static WestCacheOption buildOption(
            Map<String, String> attrs, Method m) {
        return WestCacheOption.newBuilder()
                .flusher(getAttr(attrs, "flusher"))
                .manager(getAttr(attrs, "manager"))
                .snapshot(getAttr(attrs, "snapshot"))
                .config(getAttr(attrs, "config"))
                .interceptor(getAttr(attrs, "interceptor"))
                .keyer(getAttr(attrs, "keyer"))
                .key(getAttr(attrs, "key"))
                .specs(parseSpecs(attrs))
                .method(m)
                .build();
    }

    private static Map<String, String> parseSpecs(Map<String, String> attrs) {
        String specsStr = attrs.get("specs");
        val specs = Specs.parseSpecs(specsStr);

        Anns.removeAttrs(attrs, "flusher", "manager",
                "snapshot", "config", "interceptor",
                "keyer", "key", "specs");
        specs.putAll(attrs);
        return specs;
    }

    private static String getAttr(Map<String, String> attrs,
                                  String attrName) {
        String attr = attrs.get(attrName);
        return attr == null ? "" : attr;
    }
}
