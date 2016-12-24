package com.github.bingoohuang.westcache;

import com.github.bingoohuang.westcache.base.WestCacheable;
import com.github.bingoohuang.westcache.config.DefaultWestCacheConfig;
import com.github.bingoohuang.westcache.snapshot.FileCacheSnapshot;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.val;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.github.bingoohuang.westcache.WestCacheOptions.newBuilder;
import static com.google.common.truth.Truth.assertThat;

/**
 * @author bingoohuang [bingoohuang@gmail.com] Created on 2016/12/22.
 */
public class RefreshTest {
    String north = "NORTH", south = "SOUTH";

    public static class FlushBean {
        @Getter @Setter String homeArea;

        @WestCacheable(flusher = "simple")
        public String getHomeAreaWithCache() {
            return homeArea;
        }
    }

    @Test
    public void flush() {
        FlushBean bean = WestCacheFactory.create(FlushBean.class);

        bean.setHomeArea(north);
        String cached = bean.getHomeAreaWithCache();
        assertThat(cached).isEqualTo(north);

        bean.setHomeArea(south);
        cached = bean.getHomeAreaWithCache();
        assertThat(cached).isEqualTo(north);

        val option1 = newBuilder().build();
        WestCacheRegistry.flush(option1, bean, "getHomeAreaWithCache");
        cached = bean.getHomeAreaWithCache();
        assertThat(cached).isEqualTo(south);
    }

    public static class FlushSnapshotBean {
        @Getter @Setter String homeArea;
        @Setter long sleepMillis = 150L;
        @Getter @Setter volatile boolean cacheMethodExecuted = false;

        @WestCacheable(snapshot = "file", flusher = "simple",
                config = "snapshotTestConfig") @SneakyThrows
        public String getHomeAreaWithCache() {
            // 700 milliseconds to simulate slow of reading big data
            Thread.sleep(sleepMillis);
            setCacheMethodExecuted(true);
            return homeArea;
        }
    }

    @BeforeClass
    public static void beforeClass() {
        WestCacheRegistry.registerConfig("snapshotTestConfig",
                new DefaultWestCacheConfig() {
                    @Override public long timeoutMillisToSnapshot() {
                        return 100L;
                    }
                });
    }

    @AfterClass
    public static void afterClass() {
        WestCacheRegistry.deregisterConfig("snapshotTestConfig");
    }

    @Test @SneakyThrows
    public void flushSnapshot() {
        val bigDataXXX = "SnapshotService.getBigData.XXX";

        val snapshot = new FileCacheSnapshot();
        val cacheKey = FlushSnapshotBean.class.getName() + ".getHomeAreaWithCache";
        snapshot.saveSnapshot(cacheKey, bigDataXXX);

        val bean = WestCacheFactory.create(FlushSnapshotBean.class);

        bean.setHomeArea(north);
        bean.setCacheMethodExecuted(false);
        String cached = bean.getHomeAreaWithCache();
        assertThat(cached).isEqualTo(bigDataXXX);

        do {
            Thread.sleep(50L);
        } while (!bean.isCacheMethodExecuted());

        cached = bean.getHomeAreaWithCache();
        assertThat(cached).isEqualTo(north);

        bean.setHomeArea(south);
        cached = bean.getHomeAreaWithCache();
        assertThat(cached).isEqualTo(north);

        val option2 = newBuilder().snapshot("file").build();
        WestCacheRegistry.flush(option2, bean, "getHomeAreaWithCache");
        bean.setSleepMillis(0L);
        cached = bean.getHomeAreaWithCache();

        assertThat(cached).isEqualTo(south);
    }
}
