package com.github.bingoohuang.westcache;

import com.github.bingoohuang.westcache.config.DefaultWestCacheConfig;
import com.github.bingoohuang.westcache.flusher.WestCacheFlusherBean;
import com.github.bingoohuang.westcache.outofbox.MallCacheable;
import com.github.bingoohuang.westcache.outofbox.TableCacheFlusher;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.val;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.n3r.diamond.client.impl.MockDiamondServer;

import static com.github.bingoohuang.westcache.outofbox.PackageLimitedKeyer.DATAID;
import static com.github.bingoohuang.westcache.outofbox.PackageLimitedKeyer.GROUP;
import static com.google.common.truth.Truth.assertThat;

/**
 * @author bingoohuang [bingoohuang@gmail.com] Created on 2016/12/30.
 */
public class MallCacheableTest {
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class MallBean {
        private String name;
        private int age;
    }

    @MallCacheable
    public interface MallCache {
        MallBean getMallBean();

        MallBean getMallBean2();
    }

    static MallCache mallCache = WestCacheFactory.create(MallCache.class);
    static TableCacheFlusher flusher = (TableCacheFlusher) WestCacheRegistry.getFlusher("table");

    @BeforeClass
    public static void beforeClass() {
        WestCacheRegistry.deregisterConfig("default");
        WestCacheRegistry.register("default", new DefaultWestCacheConfig(){
            @Override public long rotateIntervalMillis() {
                return 500;
            }
        });

        flusher.getDao().setup();
        MockDiamondServer.setUpMockServer();
    }

    @AfterClass
    public static void afterClass() {
        MockDiamondServer.tearDownMockServer();
    }

    @Test
    public void nonConfig() {
        try {
            MockDiamondServer.setConfigInfo(GROUP, DATAID, "");
            mallCache.getMallBean();
        } catch (RuntimeException ex) {
            assertThat(ex.getMessage()).isEqualTo(
                    "com.github.bingoohuang.westcache is not allowed for the cache key");
            return;
        }
        Assert.fail();
    }

    @Test
    public void withConfig() {
        try {
            MockDiamondServer.setConfigInfo(GROUP, DATAID, "com.github.bingoohuang.westcache");
            mallCache.getMallBean2();
        } catch (RuntimeException ex) {
            assertThat(ex.getMessage()).isEqualTo(
                    "cache key MallCacheableTest.MallCache.getMallBean2 missed executable body " +
                            "in abstract method com.github.bingoohuang.westcache.MallCacheableTest$MallCache.getMallBean2");
            return;
        }
        Assert.fail();
    }


    @Test
    public void workConfig() {
        MallBean demo = new MallBean("新几次哇一次抹黑头次", 123);

        MockDiamondServer.setConfigInfo(GROUP, DATAID, "com.github.bingoohuang.westcache");

        val cacheKey = "MallCacheableTest.MallCache.getMallBean";
        val bean = new WestCacheFlusherBean(cacheKey, "full", 0, "direct", "readBy=redis");

        long lastExecuted = flusher.getLastExecuted();
        flusher.getJedis().set(cacheKey, "{\"@type\":\"com.github.bingoohuang.westcache.MallCacheableTest$MallBean\",\"age\":123,\"name\":\"新几次哇一次抹黑头次\"}");
        flusher.getDao().addBean(bean);

        try {
            mallCache.getMallBean();
        } catch (Exception ex) {
            // ignore
        }

        // at most 15 seconds
        TableCacheFlusherTest.waitFlushRun(flusher, lastExecuted);

        MallBean mallBean2 = mallCache.getMallBean();

        assertThat(mallBean2).isEqualTo(demo);
    }
}