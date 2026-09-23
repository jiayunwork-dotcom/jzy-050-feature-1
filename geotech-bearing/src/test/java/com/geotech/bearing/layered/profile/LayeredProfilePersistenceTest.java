package com.geotech.bearing.layered.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.geotech.bearing.BearingCapacityServiceApplication;
import com.geotech.bearing.layered.domain.LayeredProfile;
import com.geotech.bearing.layered.domain.LayeredProfileAssembler;
import com.geotech.bearing.layered.domain.SoilLayer;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 分层剖面持久化测试：分层剖面（含各层）必须落到关系库，
 * 进程（Spring 上下文）重启后仍可点名取出原始分层列表，层序、层厚与各层指标不丢。
 *
 * <p>沿用单层档持久化测试的做法：文件模式 H2（PostgreSQL 兼容）+ 两个独立上下文。</p>
 */
class LayeredProfilePersistenceTest {

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
    @DisplayName("分层剖面在上下文重启后仍可点名取出（含各层，层序层厚指标不变）")
    void layeredProfileSurvivesRestart() {
        String dbFile = tempDir.resolve("geotech-layered-persist").toString();
        String name = "fill-over-alluvium-persist";

        try (ConfigurableApplicationContext first = startContext(dbFile)) {
            LayeredProfileService service = first.getBean(LayeredProfileService.class);
            List<LayeredProfileAssembler.LayerInput> layers = List.of(
                    new LayeredProfileAssembler.LayerInput(2.0, 10, 10, 18),
                    new LayeredProfileAssembler.LayerInput(3.0, 30, 30, 20),
                    new LayeredProfileAssembler.LayerInput(5.0, 5, 25, 19));
            LayeredProfile saved = service.register(name, "填土/原状土/持力层", layers);
            assertNotNull(saved);
            assertEquals(3, saved.layers().size());
        }

        try (ConfigurableApplicationContext second = startContext(dbFile)) {
            LayeredProfileService service = second.getBean(LayeredProfileService.class);
            LayeredProfile reloaded = service.get(name);

            assertEquals(name, reloaded.name());
            assertEquals("填土/原状土/持力层", reloaded.description());
            assertEquals(3, reloaded.layers().size());

            // 重启后重新累加的绝对深度区间、层厚、各层指标必须与登记一致
            SoilLayer l0 = reloaded.layers().get(0);
            assertEquals(0.0, l0.topDepthM(), 1e-9);
            assertEquals(2.0, l0.bottomDepthM(), 1e-9);
            assertEquals(10.0, l0.cohesionKpa(), 1e-9);
            assertEquals(10.0, l0.frictionAngleDeg(), 1e-9);
            assertEquals(18.0, l0.unitWeightKnM3(), 1e-9);

            SoilLayer l1 = reloaded.layers().get(1);
            assertEquals(2.0, l1.topDepthM(), 1e-9);
            assertEquals(5.0, l1.bottomDepthM(), 1e-9);
            assertEquals(30.0, l1.frictionAngleDeg(), 1e-9);

            SoilLayer l2 = reloaded.layers().get(2);
            assertEquals(5.0, l2.topDepthM(), 1e-9);
            assertEquals(10.0, l2.bottomDepthM(), 1e-9);
            assertEquals(25.0, l2.frictionAngleDeg(), 1e-9);
        }
    }
}
