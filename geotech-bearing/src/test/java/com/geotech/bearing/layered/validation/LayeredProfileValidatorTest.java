package com.geotech.bearing.layered.validation;

import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.validation.InputValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 分层剖面结构校验测试。
 *
 * <p>重点钉住：第一层必须贴地表、层间不许留空也不许重叠、层厚必须为正、
 * 每层指标沿用单层判据（含 φ≥90° 拦截），且任何错误都必须指出是第几层。</p>
 */
class LayeredProfileValidatorTest {

    private final LayeredProfileValidator validator = new LayeredProfileValidator(new InputValidator());

    private static SoilLayer layer(double top, double bottom, double c, double phi, double gamma) {
        return new SoilLayer(top, bottom, c, phi, gamma);
    }

    private static List<SoilLayer> layers(SoilLayer... ls) {
        List<SoilLayer> list = new ArrayList<>();
        for (SoilLayer l : ls) {
            list.add(l);
        }
        return list;
    }

    private LayerValidationException validateExpect(List<SoilLayer> ls) {
        return assertThrows(LayerValidationException.class, () -> validator.validate(ls));
    }

    @Test
    @DisplayName("合法的相接分层通过校验")
    void contiguousLayersPass() {
        assertDoesNotThrow(() -> validator.validate(layers(
                layer(0, 2, 10, 10, 18),
                layer(2, 5, 30, 30, 20),
                layer(5, 8, 5, 0, 17))));
    }

    @Test
    @DisplayName("分层为空 -> LAYERED_PROFILE_EMPTY（不隶属任何层）")
    void emptyProfileRejected() {
        LayerValidationException ex = validateExpect(List.of());
        assertEquals("LAYERED_PROFILE_EMPTY", ex.getReason());
        assertEquals(LayerValidationException.NO_LAYER, ex.getLayerIndex());
    }

    @Test
    @DisplayName("null 分层 -> LAYERED_PROFILE_EMPTY")
    void nullProfileRejected() {
        LayerValidationException ex = validateExpect(null);
        assertEquals("LAYERED_PROFILE_EMPTY", ex.getReason());
    }

    @Test
    @DisplayName("第一层不贴地表 -> LAYER_ONE_NOT_AT_SURFACE，指到第 1 层")
    void firstLayerMustStartAtSurface() {
        LayerValidationException ex = validateExpect(layers(
                layer(0.5, 2, 10, 10, 18),
                layer(2, 5, 30, 30, 20)));
        assertEquals("LAYER_ONE_NOT_AT_SURFACE", ex.getReason());
        assertEquals(1, ex.getLayerIndex());
        assertEquals(0, ex.getLayerIndexZeroBased());
    }

    @Test
    @DisplayName("层间留空 -> LAYER_GAP，指出下方那一层的层号")
    void gapBetweenLayersRejected() {
        LayerValidationException ex = validateExpect(layers(
                layer(0, 2, 10, 10, 18),
                layer(3, 5, 30, 30, 20)));
        assertEquals("LAYER_GAP", ex.getReason());
        assertEquals(2, ex.getLayerIndex(), "间隙在第 2 层顶面，必须指到第 2 层");
    }

    @Test
    @DisplayName("两层重叠 -> LAYER_OVERLAP，指出压进上一层的那一层")
    void overlapBetweenLayersRejected() {
        LayerValidationException ex = validateExpect(layers(
                layer(0, 2, 10, 10, 18),
                layer(1.5, 5, 30, 30, 20)));
        assertEquals("LAYER_OVERLAP", ex.getReason());
        assertEquals(2, ex.getLayerIndex());
    }

    @Test
    @DisplayName("接续面只有 1e-12 米浮点噪声：视为正好相接，放行")
    void floatingPointNoiseAtInterfaceIsAccepted() {
        assertDoesNotThrow(() -> validator.validate(layers(
                layer(0, 2, 10, 10, 18),
                layer(2 + 1e-12, 5, 30, 30, 20))));
    }

    @Test
    @DisplayName("层厚为零/为负 -> LAYER_THICKNESS_NOT_POSITIVE，指到该层")
    void nonPositiveThicknessRejected() {
        LayerValidationException zero = validateExpect(layers(
                layer(0, 0, 10, 10, 18)));
        assertEquals("LAYER_THICKNESS_NOT_POSITIVE", zero.getReason());
        assertEquals(1, zero.getLayerIndex());

        LayerValidationException negative = validateExpect(layers(
                layer(0, 2, 10, 10, 18),
                layer(2, 1.5, 30, 30, 20)));
        assertEquals("LAYER_THICKNESS_NOT_POSITIVE", negative.getReason());
        assertEquals(2, negative.getLayerIndex());
    }

    @Test
    @DisplayName("某层 φ=90° -> 沿用 FRICTION_ANGLE_OUT_OF_RANGE，并指出是该层")
    void layerFrictionAngleNinetyRejected() {
        LayerValidationException ex = validateExpect(layers(
                layer(0, 2, 10, 10, 18),
                layer(2, 5, 0, 90, 20)));
        assertEquals("FRICTION_ANGLE_OUT_OF_RANGE", ex.getReason(),
                "单层判据不能因分层新增而改变：φ=90° 必须照样拦住");
        assertEquals(2, ex.getLayerIndex());
    }

    @Test
    @DisplayName("某层重度非正 / 黏聚力为负 -> 沿用单层原因码并带层号")
    void layerPhysicalValuesReuseSingleLayerReasons() {
        LayerValidationException gamma = validateExpect(layers(
                layer(0, 2, 10, 10, 0)));
        assertEquals("UNIT_WEIGHT_NOT_POSITIVE", gamma.getReason());
        assertEquals(1, gamma.getLayerIndex());

        LayerValidationException cohesion = validateExpect(layers(
                layer(0, 2, -3, 10, 18)));
        assertEquals("COHESION_NEGATIVE", cohesion.getReason());
        assertEquals(1, cohesion.getLayerIndex());
    }
}
