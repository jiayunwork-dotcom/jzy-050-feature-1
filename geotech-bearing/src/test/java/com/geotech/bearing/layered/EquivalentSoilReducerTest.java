package com.geotech.bearing.layered;

import com.geotech.bearing.domain.ContributionWindow;
import com.geotech.bearing.domain.LayeredReduction;
import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.domain.SoilParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等效指标折算测试。
 *
 * <p>钉死的规则：c、γ 按参与厚度加权算术平均；φ 必须先转 tan、按厚度加权、
 * 再 atan 反算回角度——同一组分层数据上，正确的非线性折算与错误的
 * 「角度算术平均」必须给出不同结果。埋深所在层单独填满整个窗口时，
 * 等效指标精确退化为该层自身三个值。</p>
 */
class EquivalentSoilReducerTest {

    private static final double EPS = 1e-9;

    private final LayerContributionResolver resolver = new LayerContributionResolver();
    private final EquivalentSoilReducer reducer = new EquivalentSoilReducer();

    private LayeredReduction reduce(List<SoilLayer> layers, double depthM, double widthM) {
        return reducer.reduce(resolver.resolve(layers, depthM, widthM));
    }

    @Test
    @DisplayName("退化：埋深所在层单独填满整个窗口 -> 等效指标精确等于该层自身三值")
    void singleLayerFillingWindowDegeneratesToItself() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 2.0, 10, 10, 17),
                new SoilLayer(2.0, 3.0, 30, 30, 19));
        // Df=2.5 落在第 2 层内部，窗口 [2.5, 3.5] 被第 2 层单独填满
        LayeredReduction r = reduce(layers, 2.5, 2.0);

        assertEquals(1, r.contributions().size());
        assertEquals(30.0, r.equivalentSoil().cohesionKpa(), 0.0,
                "退化的等效黏聚力必须精确等于该层自身值");
        assertEquals(30.0, r.equivalentSoil().frictionAngleDeg(), 0.0,
                "退化的等效内摩擦角必须精确等于该层自身值（不做 tan 往返）");
        assertEquals(19.0, r.equivalentSoil().unitWeightKnM3(), 0.0,
                "退化的等效重度必须精确等于该层自身值");
    }

    @Test
    @DisplayName("退化：单层剖面任意窗口 -> 等效指标就是这一层")
    void singleLayerProfileAlwaysDegenerates() {
        List<SoilLayer> layers = List.of(new SoilLayer(0.0, 10.0, 25, 18, 18.5));
        LayeredReduction r = reduce(layers, 3.0, 4.0);
        assertEquals(new SoilParameters(25, 18, 18.5), r.equivalentSoil());
    }

    @Test
    @DisplayName("c、γ 按参与厚度做加权算术平均")
    void cohesionAndUnitWeightAreThicknessWeightedAverages() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 1.0, 10, 10, 17),
                new SoilLayer(1.0, 2.0, 30, 30, 19));
        // 窗口 [0, 3]：第 1 层 1m、第 2 层 2m
        LayeredReduction r = reduce(layers, 0.0, 6.0);

        assertEquals((10.0 * 1.0 + 30.0 * 2.0) / 3.0,
                r.equivalentSoil().cohesionKpa(), EPS, "c_eq 为厚度加权算术平均");
        assertEquals((17.0 * 1.0 + 19.0 * 2.0) / 3.0,
                r.equivalentSoil().unitWeightKnM3(), EPS, "γ_eq 为厚度加权算术平均");
    }

    @Test
    @DisplayName("φ 按 tan 加权再 atan 反算：数值与手算一致")
    void frictionAngleUsesTanWeightedAverage() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 1.0, 10, 10, 17),
                new SoilLayer(1.0, 2.0, 30, 30, 19));
        // 窗口 [0, 3]：tan(10°) 权 1、tan(30°) 权 2
        LayeredReduction r = reduce(layers, 0.0, 6.0);

        double expectedTan = (1.0 * Math.tan(Math.toRadians(10.0))
                + 2.0 * Math.tan(Math.toRadians(30.0))) / 3.0;
        double expectedPhi = Math.toDegrees(Math.atan(expectedTan));
        assertEquals(expectedPhi, r.equivalentSoil().frictionAngleDeg(), EPS,
                "φ_eq 必须由加权 tan 值反算");
        assertEquals(23.926, r.equivalentSoil().frictionAngleDeg(), 0.001,
                "手算锚点：atan((tan10°+2·tan30°)/3) ≈ 23.926°");
    }

    @Test
    @DisplayName("关键区分：同一组数据上，非线性折算 ≠ 角度算术平均折算")
    void nonlinearReductionDiffersFromArithmeticAngleAverage() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 1.0, 10, 10, 17),
                new SoilLayer(1.0, 2.0, 30, 30, 19));
        LayeredReduction r = reduce(layers, 0.0, 6.0);

        double arithmeticMeanPhi = (10.0 * 1.0 + 30.0 * 2.0) / 3.0; // 错误算法：23.333°
        assertNotEquals(arithmeticMeanPhi, r.equivalentSoil().frictionAngleDeg(), 0.1,
                "φ 若按角度算术平均将得到 " + arithmeticMeanPhi
                        + "°，正确的 tan 加权折算必须与之明显不同");
        assertTrue(r.equivalentSoil().frictionAngleDeg() > arithmeticMeanPhi,
                "tan 上凸，tan 加权反算的等效角应高于角度算术平均");
    }

    @Test
    @DisplayName("参与厚度不同则权重不同：窗口内占比大的层对等效 φ 影响更大")
    void thickerContributionWeighsMore() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 1.0, 0, 40, 18),
                new SoilLayer(1.0, 3.0, 0, 10, 18));
        // 窗口 [0, 4]：40° 层占 1m、10° 层占 3m -> 等效角应明显偏向 10°
        LayeredReduction r = reduce(layers, 0.0, 8.0);
        double phi = r.equivalentSoil().frictionAngleDeg();
        assertTrue(phi < 20.0, "10° 层占 3/4，等效角应被拉向低侧：" + phi);
        assertTrue(phi > 10.0, "等效角不应低于参与层的最小角度：" + phi);
    }

    @Test
    @DisplayName("全部参与层 φ=0：等效 φ=0，黏土极限退化路径仍可达")
    void allZeroFrictionLayersReduceToZero() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 1.0, 15, 0, 17),
                new SoilLayer(1.0, 2.0, 25, 0, 18));
        LayeredReduction r = reduce(layers, 0.0, 6.0);
        assertEquals(0.0, r.equivalentSoil().frictionAngleDeg(), 0.0,
                "tan(0)=0 加权和仍为 0，等效 φ 精确为 0");
        assertEquals((15.0 * 1.0 + 25.0 * 2.0) / 3.0,
                r.equivalentSoil().cohesionKpa(), EPS);
    }

    @Test
    @DisplayName("折算明细摊开：窗口上下界与各层贡献厚度随结果一并给出")
    void reductionExposesWindowAndContributions() {
        List<SoilLayer> layers = List.of(
                new SoilLayer(0.0, 2.0, 10, 10, 17),
                new SoilLayer(2.0, 3.0, 30, 30, 19));
        LayeredReduction r = reduce(layers, 1.0, 4.0);

        assertEquals(1.0, r.windowTopM(), EPS);
        assertEquals(3.0, r.windowBottomM(), EPS);
        assertEquals(2, r.contributions().size());
        assertEquals(2.0, r.totalContributingThicknessM(), EPS);
    }
}
