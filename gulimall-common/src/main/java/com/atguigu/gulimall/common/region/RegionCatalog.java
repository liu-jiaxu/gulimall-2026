package com.atguigu.gulimall.common.region;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * 省市区基础数据。
 *
 * <p>数据源是 common 模块里的 {@code region/pca-code.json}，
 * 页面不再写死「北京/上海/天津…」这些假列表，统一从这里取。
 * 数据量固定且很小，进程内只解析一次，不占 Redis。
 */
@Slf4j
public final class RegionCatalog {

    private static final String RESOURCE = "/region/pca-code.json";

    private static volatile List<RegionNode> provinces = Collections.emptyList();

    private RegionCatalog() {
    }

    public static List<RegionNode> provinces() {
        ensureLoaded();
        return provinces;
    }

    /** 某个省下的市 */
    public static List<RegionNode> cities(String provinceCode) {
        for (RegionNode province : provinces()) {
            if (province.getCode().equals(provinceCode)) {
                return province.getChildren() == null
                        ? Collections.emptyList() : province.getChildren();
            }
        }
        return Collections.emptyList();
    }

    /** 某个市下的区县 */
    public static List<RegionNode> areas(String cityCode) {
        for (RegionNode province : provinces()) {
            if (province.getChildren() == null) {
                continue;
            }
            for (RegionNode city : province.getChildren()) {
                if (city.getCode().equals(cityCode)) {
                    return city.getChildren() == null
                            ? Collections.emptyList() : city.getChildren();
                }
            }
        }
        return Collections.emptyList();
    }

    private static void ensureLoaded() {
        if (!provinces.isEmpty()) {
            return;
        }
        synchronized (RegionCatalog.class) {
            if (!provinces.isEmpty()) {
                return;
            }
            try (InputStream in = RegionCatalog.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    log.error("[region] classpath 下没有 {}", RESOURCE);
                    return;
                }
                provinces = new ObjectMapper().readValue(in,
                        new TypeReference<List<RegionNode>>() {
                        });
                log.info("[region] 已加载省市区数据：{} 个省级行政区", provinces.size());
            } catch (Exception e) {
                log.error("[region] 省市区数据加载失败", e);
            }
        }
    }
}
