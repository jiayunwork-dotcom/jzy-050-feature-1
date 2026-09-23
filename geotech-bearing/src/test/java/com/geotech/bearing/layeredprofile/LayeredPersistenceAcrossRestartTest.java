package com.geotech.bearing.layeredprofile;

import com.geotech.bearing.BearingCapacityServiceApplication;
import com.geotech.bearing.domain.LayeredBearingResult;
import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.layered.LayeredBearingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层剖面持久化测试：分层数据必须落到关系型数据库，进程（Spring 上下文）重启后
 * 仍能按名取出原始分层列表（顺序与数值不变），并能直接点名核算。
 *
 * <p>与单层参数档的持久化测试同款手法：文件模式 H2（PostgreSQL 兼容）作为关系库，
 * 连续启动两个独立上下文复用同一库文件。</p>
 */
class LayeredPersistenceAcrossRestartTest {

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext startContext(String dbFile) {
        String[] args = {
                "--spring.datasource.url=jdbc:h2:file:" + dbFile
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "--spring.main.web-application-type=none",
                "--logging.level.root=WARN",
                "--logging.level.com.geotech=WARN"
        };
        return new SpringApplicationBuilder(BearingCapacityServiceApplication.class)
                .run(args);
    }

    @Test
    @DisplayName("分层剖面在上下文重启后仍可点名取出（层序与数值不变）并直接核算")
    void layeredProfileSurvivesRestart() {
        String dbFile = tempDir.resolve("geotech-layered-persist").toString();
        String profileName = "slope-section-persist";

        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 1.5, 5.0, 12.0, 17.5),
                new SoilLayer(1.5, 2.5, 25.0, 28.0, 19.0),
                new SoilLayer(4.0, 3.0, 40.0, 34.0, 20.0));

        // 第一个「进程」：登记后关闭
        try (ConfigurableApplicationContext first = startContext(dbFile)) {
            LayeredProfileService service = first.getBean(LayeredProfileService.class);
            LayeredProfileEntity saved = service.register(
                    profileName, "持久化测试用三层剖面", layers);
            assertNotNull(saved.getId());
            assertTrue(saved.getId() > 0);
        }

        // 第二个「进程」：复用同一库文件，不重新登记，直接点名取层并核算
        try (ConfigurableApplicationContext second = startContext(dbFile)) {
            LayeredProfileService service = second.getBean(LayeredProfileService.class);
            LayeredProfileEntity reloaded = service.get(profileName);

            assertEquals(profileName, reloaded.getName());
            assertEquals("持久化测试用三层剖面", reloaded.getDescription());
            assertEquals(3, reloaded.getLayers().size(), "三层必须全部持久化");

            // 层序与数值原样还原
            List<SoilLayer> restored = service.requireLayers(profileName);
            assertEquals(3, restored.size());
            for (int i = 0; i < layers.size(); i++) {
                assertEquals(layers.get(i), restored.get(i),
                        "第 " + (i + 1) + " 层重启后必须原样还原");
            }

            // 重启后直接点名核算：折算与计算链路完整可用
            LayeredBearingService bearingService = second.getBean(LayeredBearingService.class);
            LayeredBearingResult result =
                    bearingService.calculate(profileName, null, 3.0, 1.0, null);
            assertTrue(Double.isFinite(result.qu()) && result.qu() > 0,
                    "重启后点名核算应给出有限正的 qu");
            assertEquals(1.0, result.reduction().windowTopM(), 1e-9);
            assertEquals(2.5, result.reduction().windowBottomM(), 1e-9);
        }
    }
}
