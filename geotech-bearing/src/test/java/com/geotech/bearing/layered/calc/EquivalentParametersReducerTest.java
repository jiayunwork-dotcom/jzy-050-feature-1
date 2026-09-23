package com.geotech.bearing.layered.calc;

import com.geotech.bearing.domain.SoilParameters;
import com.geotech.bearing.layered.domain.LayerContribution;
import com.geotech.bearing.layered.domain.SoilLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等效指标折算测试。
 *
 * <p>核心钉死：</p>
 * <ol>
 *   <li>c、γ 按窗口内厚度加权算术平均；</li>
 *   <li><b>φ 必须先换 tan、厚度加权、再 atan 反算</b>，
 *       在同一组分层上与「错误的角度算术平均」结果不同；</li>
 *   <li>单一参与层填满窗口时，等效结果严格退化为该层自身三个指标
 *       （含 φ 的精确相等，不允许 tan/atan 往返噪声）；</li>
 *   <li>各层 φ=0 时等效 φ=0，黏土极限分支仍可被下游识别。</li>
 * </ol>
 */
class EquivalentParametersReducerTest {

    private final EquivalentParametersReducer reducer = new EquivalentParametersReducer();

    private static final double EPS = 1e-12;

    private static LayerContribution contribution(int index, SoilLayer layer, double thickness) {
        return new LayerContribution(index, layer, thickness);
    }

    @Test
    @DisplayName("c、γ：按窗口内厚度加权算术平均")
    void cohesionAndUnitWeightAreThicknessWeightedMeans() {
        // 窗口内两层各占 1m 与 3m
        List<LayerContribution> contributions = List.of(
                contribution(0, new SoilLayer(1, 2, 10, 30, 18), 1.0),
                contribution(1, new SoilLayer(2, 6, 50, 0, 22), 3.0));

        SoilParameters eq = reducer.reduce(contributions);
        assertEquals((10 * 1 + 50 * 3) / 4.0, eq.cohesionKpa(), EPS,
                "c_eq = Σ(t·c)/Σt");
        assertEquals((18 * 1 + 22 * 3) / 4.0, eq.unitWeightKnM3(), EPS,
                "γ_eq = Σ(t·γ)/Σt");
    }

    @Test
    @DisplayName("φ：tan 加权再 atan 反算，且与错误的角度算术平均明确不同（本次正确性关键）")
    void frictionAngleUsesTanWeightingNotArithmeticMean() {
        // 两层各占 1m：φ=10° 与 φ=30°
        List<LayerContribution> contributions = List.of(
                contribution(0, new SoilLayer(1, 2, 0, 10, 18), 1.0),
                contribution(1, new SoilLayer(2, 3, 0, 30, 18), 1.0));

        SoilParameters eq = reducer.reduce(contributions);

        double expectedTanMean = (Math.tan(Math.toRadians(10)) + Math.tan(Math.toRadians(30))) / 2.0;
        double expectedPhi = Math.toDegrees(Math.atan(expectedTanMean));
        assertEquals(expectedPhi, eq.frictionAngleDeg(), 1e-12,
                "φ_eq = atan(Σ(t·tanφ)/Σt)");

        double wrongArithmetic = reducer.wrongArithmeticMeanFrictionAngle(contributions);
        assertEquals(20.0, wrongArithmetic, 1e-12, "错误做法：直接对角度取平均会得到 20°");
        assertNotEquals(wrongArithmetic, eq.frictionAngleDeg(), 1e-9,
                "非线性折算与角度算术平均必须给出不同的 φ");
        // tan 是凸函数，tan 加权反算结果应大于算术平均角度
        assertTrue(eq.frictionAngleDeg() > wrongArithmetic,
                "φ_eq(tan 加权反算)=" + eq.frictionAngleDeg()
                        + " 应大于错误算术平均 " + wrongArithmetic);
    }

    @Test
    @DisplayName("不等厚窗口下非线性折算仍区别于算术平均（按厚度权重）")
    void tanWeightingWithUnequalThickness() {
        List<LayerContribution> contributions = List.of(
                contribution(0, new SoilLayer(2, 3, 0, 0, 18), 1.0),
                contribution(1, new SoilLayer(3, 8, 0, 40, 18), 4.0));

        SoilParameters eq = reducer.reduce(contributions);
        double expectedPhi = Math.toDegrees(Math.atan(
                (1 * Math.tan(Math.toRadians(0)) + 4 * Math.tan(Math.toRadians(40))) / 5.0));
        assertEquals(expectedPhi, eq.frictionAngleDeg(), 1e-12);

        double wrongArithmetic = reducer.wrongArithmeticMeanFrictionAngle(contributions);
        assertEquals(32.0, wrongArithmetic, 1e-12);
        assertNotEquals(wrongArithmetic, eq.frictionAngleDeg(), 1e-9);
    }

    @Test
    @DisplayName("单层填满整窗口：等效结果严格等于该层自身 c/φ/γ（专门退化用例）")
    void singleParticipatingLayerDegeneratesToItsOwnParameters() {
        SoilLayer layer = new SoilLayer(1.3, 3.3, 27.5, 24.333333333, 18.75);
        List<LayerContribution> contributions = new ArrayList<>();
        // 模拟真实窗口切分结果：其它层贡献为 0，只有本层贡献了整个窗口
        contributions.add(contribution(0, new SoilLayer(0, 1.3, 0, 0, 10), 0.0));
        contributions.add(contribution(1, layer, 2.0));
        contributions.add(contribution(2, new SoilLayer(3.3, 9, 0, 0, 10), 0.0));

        SoilParameters eq = reducer.reduce(contributions);
        assertEquals(27.5, eq.cohesionKpa(), 0.0,
                "单参与层时 c_eq 必须精确等于该层 c，不掺任何加权");
        assertEquals(24.333333333, eq.frictionAngleDeg(), 0.0,
                "单参与层时 φ_eq 必须精确等于该层 φ，不允许 tan/atan 往返误差");
        assertEquals(18.75, eq.unitWeightKnM3(), 0.0,
                "单参与层时 γ_eq 必须精确等于该层 γ");
    }

    @Test
    @DisplayName("各层 φ=0：等效 φ=0（下游黏土极限分支可识别），c、γ 照常加权")
    void allZeroFrictionDegradesToZero() {
        List<LayerContribution> contributions = List.of(
                contribution(0, new SoilLayer(0, 2, 40, 0, 19), 2.0),
                contribution(1, new SoilLayer(2, 5, 20, 0, 17), 3.0));
        SoilParameters eq = reducer.reduce(contributions);
        assertEquals(0.0, eq.frictionAngleDeg(), EPS);
        assertEquals(28.0, eq.cohesionKpa(), EPS);
        assertEquals(17.8, eq.unitWeightKnM3(), EPS);
    }

    @Test
    @DisplayName("没有任何参与层 -> 抛异常，不允许产生 NaN 等效指标")
    void noParticipatingLayerRejected() {
        List<LayerContribution> contributions = List.of(
                contribution(0, new SoilLayer(0, 2, 10, 10, 18), 0.0));
        assertThrows(IllegalStateException.class, () -> reducer.reduce(contributions));
    }
}
