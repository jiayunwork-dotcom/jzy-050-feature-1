package com.geotech.bearing.layered;

import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.validation.InputValidator;
import com.geotech.bearing.validation.InvalidInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层剖面结构校验测试：贴地表、层间连续（不留空、不重叠）、层厚为正，
 * 以及每层物理指标与单层同款的判据——全部在折算之前被拦下，并指出是第几层。
 */
class LayeredProfileValidatorTest {

    private final LayeredProfileValidator validator =
            new LayeredProfileValidator(new InputValidator());

    private InvalidInputException expectInvalid(Runnable r) {
        return assertThrows(InvalidInputException.class, r::run);
    }

    private static SoilLayer layer(double top, double thickness,
                                   double c, double phi, double gamma) {
        return new SoilLayer(top, thickness, c, phi, gamma);
    }

    @Test
    @DisplayName("空剖面（null 或零层）-> LAYERED_PROFILE_EMPTY")
    void emptyProfileRejected() {
        assertEquals("LAYERED_PROFILE_EMPTY",
                expectInvalid(() -> validator.validate(null)).getReason());
        assertEquals("LAYERED_PROFILE_EMPTY",
                expectInvalid(() -> validator.validate(List.of())).getReason());
    }

    @Test
    @DisplayName("第一层不贴地表（顶深 > 0 或 < 0）-> FIRST_LAYER_NOT_AT_SURFACE")
    void firstLayerMustStartAtSurface() {
        InvalidInputException ex = expectInvalid(() -> validator.validate(List.of(
                layer(0.5, 2.0, 10, 20, 18))));
        assertEquals("FIRST_LAYER_NOT_AT_SURFACE", ex.getReason());
        assertTrue(ex.getMessage().contains("第 1 层"), "错误应指出是第 1 层：" + ex.getMessage());

        InvalidInputException negative = expectInvalid(() -> validator.validate(List.of(
                layer(-0.5, 2.0, 10, 20, 18))));
        assertEquals("FIRST_LAYER_NOT_AT_SURFACE", negative.getReason());
    }

    @Test
    @DisplayName("层厚为零或为负 -> LAYER_THICKNESS_NOT_POSITIVE，并指出第几层")
    void thicknessMustBePositive() {
        InvalidInputException zero = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 0.0, 10, 20, 18))));
        assertEquals("LAYER_THICKNESS_NOT_POSITIVE", zero.getReason());
        assertTrue(zero.getMessage().contains("第 1 层"));

        InvalidInputException negative = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(2.0, -1.0, 30, 30, 19))));
        assertEquals("LAYER_THICKNESS_NOT_POSITIVE", negative.getReason());
        assertTrue(negative.getMessage().contains("第 2 层"),
                "第二层出问题应指出第 2 层：" + negative.getMessage());
    }

    @Test
    @DisplayName("层间留空 -> LAYER_GAP，并指出间隙出现在第几层")
    void gapBetweenLayersRejected() {
        InvalidInputException ex = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(2.5, 3.0, 30, 30, 19)))); // 2.0 ~ 2.5 之间留空 0.5m
        assertEquals("LAYER_GAP", ex.getReason());
        assertTrue(ex.getMessage().contains("第 2 层"), "错误应指出是第 2 层：" + ex.getMessage());
    }

    @Test
    @DisplayName("层间重叠 -> LAYER_OVERLAP，并指出重叠出现在第几层")
    void overlapBetweenLayersRejected() {
        InvalidInputException ex = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(1.5, 3.0, 30, 30, 19)))); // 顶面 1.5 压进上一层 [0,2) 之内
        assertEquals("LAYER_OVERLAP", ex.getReason());
        assertTrue(ex.getMessage().contains("第 2 层"), "错误应指出是第 2 层：" + ex.getMessage());
    }

    @Test
    @DisplayName("第三层与第二层之间留空同样被拦（问题不只在开头）")
    void gapDeeperInProfileRejected() {
        InvalidInputException ex = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(2.0, 3.0, 30, 30, 19),
                layer(5.5, 1.0, 50, 35, 20)))); // 5.0 ~ 5.5 留空
        assertEquals("LAYER_GAP", ex.getReason());
        assertTrue(ex.getMessage().contains("第 3 层"));
    }

    @Test
    @DisplayName("合法的三层剖面（贴地表、严丝合缝、层厚为正）通过校验")
    void validProfilePasses() {
        validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(2.0, 3.0, 30, 30, 19),
                layer(5.0, 4.0, 50, 35, 20)));
    }

    @Test
    @DisplayName("单层剖面（仅一层贴地表）合法")
    void singleLayerProfilePasses() {
        validator.validate(List.of(layer(0.0, 5.0, 10, 20, 18)));
    }

    @Test
    @DisplayName("某层物理指标非法：沿用单层判据并指出第几层")
    void layerPhysicsReuseSingleLayerRules() {
        InvalidInputException cohesion = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, -5, 20, 18))));
        assertEquals("COHESION_NEGATIVE", cohesion.getReason());
        assertTrue(cohesion.getMessage().contains("第 1 层"));

        InvalidInputException friction = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(2.0, 3.0, 30, 90, 19))));
        assertEquals("FRICTION_ANGLE_OUT_OF_RANGE", friction.getReason());
        assertTrue(friction.getMessage().contains("第 2 层"),
                "φ=90 必须按单层判据拦在第二层：" + friction.getMessage());

        InvalidInputException unitWeight = expectInvalid(() -> validator.validate(List.of(
                layer(0.0, 2.0, 10, 20, 18),
                layer(2.0, 3.0, 30, 30, 0))));
        assertEquals("UNIT_WEIGHT_NOT_POSITIVE", unitWeight.getReason());
        assertTrue(unitWeight.getMessage().contains("第 2 层"));
    }

    @Test
    @DisplayName("层列表中混入 null 层 -> LAYER_MISSING，并指出第几层")
    void nullLayerRejected() {
        InvalidInputException ex = expectInvalid(() -> validator.validate(
                java.util.Arrays.asList(layer(0.0, 2.0, 10, 20, 18), null)));
        assertEquals("LAYER_MISSING", ex.getReason());
        assertTrue(ex.getMessage().contains("第 2 层"));
    }
}
