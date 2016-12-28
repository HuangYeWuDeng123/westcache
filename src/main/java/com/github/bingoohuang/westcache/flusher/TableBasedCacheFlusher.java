package com.github.bingoohuang.westcache.flusher;

import com.github.bingoohuang.westcache.utils.WestCacheOption;
import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.Cleanup;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.val;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/*
ORACLE SQL:

 DROP TABLE WESTCACHE_FLUSHER;
 CREATE TABLE WESTCACHE_FLUSHER(
    CACHE_KEY VARCHAR2(2000 BYTE) NOT NULL PRIMARY KEY,
	KEY_MATCH VARCHAR2(20 BYTE) DEFAULT 'full' NOT NULL,
	VALUE_VERSION NUMBER DEFAULT 0 NOT NULL,
	CACHE_STATE NUMBER DEFAULT 1 NOT NULL,
	VALUE_TYPE VARCHAR2(20 BYTE) DEFAULT 'none' NOT NULL,
	DIRECT_VALUE LONG
   ) ;

   COMMENT ON COLUMN WESTCACHE_FLUSHER.CACHE_KEY IS 'cache key';
   COMMENT ON COLUMN WESTCACHE_FLUSHER.KEY_MATCH IS 'full:full match,prefix:prefix match';
   COMMENT ON COLUMN WESTCACHE_FLUSHER.VALUE_VERSION IS 'version of cache, increment it to update cache';
   COMMENT ON COLUMN WESTCACHE_FLUSHER.DIRECT_VALUE IS 'direct json value for the cache';
   COMMENT ON COLUMN WESTCACHE_FLUSHER.CACHE_STATE IS '0 disabled 1 enabled';
   COMMENT ON COLUMN WESTCACHE_FLUSHER.VALUE_TYPE IS 'value access type, direct: use direct json in DIRECT_VALUE field';

MySql SQL:
   DROP TABLE IF EXISTS WESTCACHE_FLUSHER;
   CREATE TABLE WESTCACHE_FLUSHER(
    CACHE_KEY VARCHAR(2000) NOT NULL PRIMARY KEY COMMENT 'cache key',
	KEY_MATCH VARCHAR(20) DEFAULT 'full' NOT NULL COMMENT 'full:full match,prefix:prefix match',
	VALUE_VERSION TINYINT DEFAULT 0 NOT NULL COMMENT 'version of cache, increment it to update cache',
	CACHE_STATE TINYINT DEFAULT 1 NOT NULL COMMENT 'direct json value for the cache',
	VALUE_TYPE VARCHAR(20) DEFAULT 'none' NOT NULL COMMENT 'value access type, direct: use direct json in DIRECT_VALUE field',
	DIRECT_VALUE TEXT
   ) ;

 */

/**
 * @author bingoohuang [bingoohuang@gmail.com] Created on 2016/12/28.
 */
public abstract class TableBasedCacheFlusher extends SimpleCacheFlusher {
    ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    Lock readLock = readWriteLock.readLock();
    Lock writeLock = readWriteLock.writeLock();
    List<WestCacheFlusherBean> tableRows = Lists.newArrayList();
    @Getter volatile long lastExecuted = -1;
    Cache<String, Optional<Map<String, Object>>> prefixDirectCache = CacheBuilder.newBuilder().build();

    @Override @SneakyThrows
    public boolean isKeyEnabled(WestCacheOption option, String cacheKey) {
        if (lastExecuted == -1) startupRotateChecker(option);

        readLock.lock();
        @Cleanup val i = new Closeable() {
            @Override public void close() throws IOException {
                readLock.unlock();
            }
        };

        val bean = findBean(cacheKey);
        return bean != null;
    }

    @Override
    public <T> T getDirectValue(WestCacheOption option, String cacheKey) {
        val bean = findBean(cacheKey);
        if (bean == null) return null;

        if (!"direct".equals(bean.getValueType())) return null;

        if ("full".equals(bean.getKeyMatch())) {
            return (T) readDirectValue(bean);
        } else if ("prefix".equals(bean.getKeyMatch())) {
            val subKey = cacheKey.substring(bean.getCacheKey().length() + 1);
            return (T) readSubDirectValue(bean, subKey);
        }

        return null;
    }


    protected abstract List<WestCacheFlusherBean> queryAllBeans();

    protected abstract Object readDirectValue(WestCacheFlusherBean bean);

    protected void flushPrefix(String prefixKey) {
        prefixDirectCache.invalidate(prefixKey);
    }

    @SneakyThrows
    private <T> T readSubDirectValue(final WestCacheFlusherBean bean, String subKey) {
        val optional = prefixDirectCache.get(bean.getCacheKey(), new Callable<Optional<Map<String, Object>>>() {
            @Override
            public Optional<Map<String, Object>> call() throws Exception {
                val map = (Map<String, Object>) readDirectValue(bean);

                return Optional.fromNullable(map);
            }
        });

        return optional.isPresent() ? (T) optional.get().get(subKey) : null;
    }

    protected WestCacheFlusherBean findBean(String cacheKey) {
        for (val bean : tableRows) {
            if ("full".equals(bean.getKeyMatch())) {
                if (bean.getCacheKey().equals(cacheKey)) return bean;
            } else if ("prefix".equals(bean.getKeyMatch())) {
                if (isPrefix(cacheKey, bean.getCacheKey())) return bean;
            }
        }
        return null;
    }

    protected void startupRotateChecker(WestCacheOption option) {
        lastExecuted = 0;
        val config = option.getConfig();

        checkBeans();
        long intervalMillis = config.rotateCheckIntervalMillis();
        config.executorService().scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                checkBeans();
            }
        }, intervalMillis, intervalMillis, MILLISECONDS);
    }


    @SneakyThrows
    protected void checkBeans() {
        val beans = queryAllBeans();
        if (beans.equals(tableRows)) return;

        diff(tableRows, beans);

        writeLock.lock();
        @Cleanup val i = new Closeable() {
            @Override public void close() throws IOException {
                writeLock.unlock();
            }
        };

        tableRows = beans;
        lastExecuted = System.currentTimeMillis();
    }

    protected void diff(List<WestCacheFlusherBean> table,
                        List<WestCacheFlusherBean> beans) {
        Map<String, WestCacheFlusherBean> flushKeys = Maps.newHashMap();
        for (val bean : table) {
            val found = find(bean, beans);
            if (found == null ||
                    found.getValueVersion() != bean.getValueVersion()) {
                flushKeys.put(bean.getCacheKey(), bean);
            }
        }

        if (flushKeys.isEmpty()) return;

        Set<String> prefixKeys = Sets.newHashSet();
        Set<String> fullKeys = Sets.newHashSet();
        for (val key : getRegistry().asMap().keySet()) {
            if (flushKeys.containsKey(key)) {
                fullKeys.add(key);
            } else {
                for (val bean : flushKeys.values()) {
                    if (!"prefix".equals(bean.getKeyMatch())) continue;
                    if (isPrefix(key, bean.getCacheKey())) {
                        fullKeys.add(key);
                        prefixKeys.add(bean.getCacheKey());
                    }
                }
            }
        }

        for (String fullKey : fullKeys) {
            flush(fullKey);
        }

        for (String prefixKey : prefixKeys) {
            flushPrefix(prefixKey);
        }
    }


    protected WestCacheFlusherBean find(
            WestCacheFlusherBean bean,
            List<WestCacheFlusherBean> beans) {
        for (val newbean : beans) {
            if (bean.getCacheKey().equals(newbean.getCacheKey()))
                return newbean;
        }
        return null;
    }

    protected boolean isPrefix(String str, String prefix) {
        if (!str.startsWith(prefix)) return false;
        if (str.length() == prefix.length()) return true;

        char nextChar = str.charAt(prefix.length());
        return nextChar == '.' || nextChar == '_';
    }
}