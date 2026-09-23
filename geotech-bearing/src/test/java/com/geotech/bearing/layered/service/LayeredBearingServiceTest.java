package com.geotech.bearing.layered.service;

import com.geotech.bearing.calculation.BearingFactorsCalculator;
import com.geotech.bearing.calculation.TerzaghiBearingCalculator;
import com.geotech.bearing.calculation.TerzaghiShapeFactorProvider;
import com.geotech.bearing.domain.FoundationShape;
import com.geotech.bearing.domain.SoilParameters;
import com.geotech.bearing.layered.calc.EquivalentParametersReducer;
import com.geotech.bearing.layered.calc.LayeredBearingResult;
import com.geotech.bearing.layered.calc.ReductionWindowResolver;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.layered.validation.LayerValidationException;
import com.geotech.bearing.validation.InputValidator;
import com.geotech.bearing.validation.InvalidInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 分层核算编排测试（不启 Spring，直接组装生产用的真实组件）。
 *
 * <p>验证：折算窗口随埋深切换起点、单层填满窗口退化、等效指标走既有计算路径
 * （因子 / 黏土极限 / 三项叠加 / 形状修正一律不重写）、结构错误在折算前拦截。</p>
 */
class LayeredBearingServiceTest {

    private final InputValidator validator = new InputValidator();
    private final TerzaghiBearingCalculator bearingCalculator = new TerzaghiBearingCalculator(
            new BearingFactorsCalculator(), new TerzaghiShapeFactorProvider());

    private final LayeredBearingService service = new LayeredBearingService(
            null, // 本测试只走临时分层路径，不需要分层剖面仓储
            new com.geotech.bearing.layered.validation.LayeredProfileValidator(validator),
            new ReductionWindowResolver(),
            new EquivalentParametersReducer(),
            bearingCalculator,
            validator);

    private static final double EPS = 1e-9;

    /** 0-2 填土(10,10,18) / 2-5 原状土(30,30,20) / 5-10 持力层(5,25,19)。 */
    private static final List<SoilLayer> PROFILE = List.of(
            new SoilLayer(0, 2, 10, 10, 18),
            new SoilLayer(2, 5, 30, 30, 20),
            new SoilLayer(5, 10, 5, 25, 19));

    @Test
    @DisplayName("临时分层核算：窗口 [1,3]，两层各 1m，等效指标与 qu 按 tan 加权口径计算")
    void inlineCalculationReducesWindowAndRunsExistingKernel() {
        LayeredBearingResult r = service.calculateInline(PROFILE, 4.0, 1.0, "STRIP");

        assertEquals(1.0, r.window().topDepthM(), EPS);
        assertEquals(3.0, r.window().bottomDepthM(), EPS);
        assertEquals(1.0, r.contributions().get(0).contributedThicknessM(), EPS);
        assertEquals(1.0, r.contributions().get(1).contributedThicknessM(), EPS);
        assertEquals(0.0, r.contributions().get(2).contributedThicknessM(), EPS);

        SoilParameters eq = r.equivalentSoil();
        assertEquals(20.0, eq.cohesionKpa(), EPS, "c_eq=(10+30)/2");
        assertEquals(19.0, eq.unitWeightKnM3(), EPS, "γ_eq=(18+20)/2");
        double expectedPhi = Math.toDegrees(Math.atan(
                (Math.tan(Math.toRadians(10)) + Math.tan(Math.toRadians(30))) / 2.0));
        assertEquals(expectedPhi, eq.frictionAngleDeg(), 1e-12,
                "φ_eq 必须是 tan 加权反算值，而不是 20°");

        // qu 必须等于「等效指标 + 既有三项叠加」在同一几何下独立复算的结果
        double expectedQu = bearingCalculator.calculate(
                eq, new com.geotech.bearing.domain.FoundationGeometry(4.0, 1.0, FoundationShape.STRIP)
        ).qu();
        assertEquals(expectedQu, r.quKpa(), 1e-10);
        // 最终因子也来自既有因子内核（直接喂等效 φ 应得到同一组因子）
        assertEquals(new BearingFactorsCalculator().calculate(eq.frictionAngleDeg()).nq(),
                r.bearingResult().factors().nq(), 1e-12);
    }

    @Test
    @DisplayName("单层填满整窗口：等效指标退化为该层自身，分层 qu 与该层单层核算完全一致")
    void windowFilledByOneLayerDegeneratesToSingleLayerResult() {
        // Df=0、B=4 -> 窗口 [0,2]，恰好等于第一层
        LayeredBearingResult layered =
                service.calculateInline(PROFILE, 4.0, 0.0, "STRIP");

        SoilParameters firstLayer = new SoilParameters(10, 10, 18);
        assertEquals(10.0, layered.equivalentSoil().cohesionKpa(), 0.0);
        assertEquals(10.0, layered.equivalentSoil().frictionAngleDeg(), 0.0);
        assertEquals(18.0, layered.equivalentSoil().unitWeightKnM3(), 0.0);

        double singleLayerQu = bearingCalculator.calculate(
                firstLayer,
                new com.geotech.bearing.domain.FoundationGeometry(4.0, 0.0, FoundationShape.STRIP)
        ).qu();
        assertEquals(singleLayerQu, layered.quKpa(), 0.0,
                "退化情形必须与直接拿第一层做单层核算完全一致");
    }

    @Test
    @DisplayName("窗口探出剖面底面：只用实际可取到的层（truncated），仍给出结果")
    void truncatedWindowUsesOnlyAvailableLayers() {
        LayeredBearingResult r = service.calculateInline(PROFILE, 12.0, 6.0, "STRIP");
        // [6,12] 实际只取到 6-10 的第 3 层 -> 退化为第 3 层
        assertEquals(true, r.truncated());
        assertEquals(4.0, r.actualWindowLengthM(), EPS);
        assertEquals(5.0, r.equivalentSoil().cohesionKpa(), 0.0);
        assertEquals(25.0, r.equivalentSoil().frictionAngleDeg(), 0.0);
        assertEquals(19.0, r.equivalentSoil().unitWeightKnM3(), 0.0);
    }

    @Test
    @DisplayName("层间留空：进入折算前就抛 LAYER_GAP 且指到第 2 层，绝不静默继续算")
    void gapRejectedBeforeReduction() {
        List<SoilLayer> gapped = List.of(
                new SoilLayer(0, 2, 10, 10, 18),
                new SoilLayer(3, 5, 30, 30, 20));
        LayerValidationException ex = assertThrows(LayerValidationException.class,
                () -> service.calculateInline(gapped, 4.0, 1.0, "STRIP"));
        assertEquals("LAYER_GAP", ex.getReason());
        assertEquals(2, ex.getLayerIndex());
    }

    @Test
    @DisplayName("埋深超过剖面底面：EMBEDMENT_BELOW_PROFILE 结构化错误")
    void embedmentBelowProfileRejected() {
        InvalidInputException ex = assertThrows(InvalidInputException.class,
                () -> service.calculateInline(PROFILE, 4.0, 10.0, "STRIP"));
        assertEquals("EMBEDMENT_BELOW_PROFILE", ex.getReason());
    }

    @Test
    @DisplayName("方形形状修正沿用既有内核：sc=1.3、sγ=0.8，三项都被修正")
    void squareShapeFlowsThroughExistingKernel() {
        LayeredBearingResult r = service.calculateInline(PROFILE, 4.0, 1.0, "SQUARE");
        assertEquals(1.3, r.bearingResult().shapeFactors().sc(), EPS);
        assertEquals(1.0, r.bearingResult().shapeFactors().sq(), EPS);
        assertEquals(0.8, r.bearingResult().shapeFactors().sGamma(), EPS);
    }

    @Test
    @DisplayName("宽度非正：沿用 WIDTH_NOT_POSITIVE，且在折算之前（分层本身合法也拒绝）")
    void invalidWidthStillRejected() {
        InvalidInputException ex = assertThrows(InvalidInputException.class,
                () -> service.calculateInline(PROFILE, 0.0, 1.0, "STRIP"));
        assertEquals("WIDTH_NOT_POSITIVE", ex.getReason());
    }
}
