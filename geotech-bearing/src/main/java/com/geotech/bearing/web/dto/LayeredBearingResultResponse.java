package com.geotech.bearing.web.dto;

import com.geotech.bearing.domain.BearingResult;
import com.geotech.bearing.layered.calc.LayeredBearingResult;
import com.geotech.bearing.layered.domain.SoilLayer;

import java.util.List;

/**
 * 分层剖面核算结果对外视图。
 *
 * <p>除最终承载力因子与 qu 外，<b>折算过程完全摊开</b>：折算窗口上下界、
 * 每层贡献厚度、折算后的等效 c/φ/γ，以及等效指标走既有单层路径得到的完整核算结果，
 * 方便人工核对折算这一步是否正确，而不是只给一个黑箱数字。</p>
 */
public record LayeredBearingResultResponse(String source,
                                           String profileName,
                                           ReductionWindowView window,
                                           boolean windowTruncated,
                                           double actualWindowThicknessM,
                                           List<LayerContributionView> layerContributions,
                                           EquivalentSoilView equivalentSoil,
                                           BearingResultResponse bearing) {

    /** 折算窗口 [Df, Df+B/2]。 */
    public record ReductionWindowView(double topDepthM,
                                      double bottomDepthM,
                                      double lengthM) {
    }

    /** 某一层的折算贡献：层号、顶/底深度、自身指标、窗口内实际贡献厚度、占窗口厚度比例。 */
    public record LayerContributionView(int layerIndex,
                                        double topDepthM,
                                        double bottomDepthM,
                                        double thicknessM,
                                        double cohesionKpa,
                                        double frictionAngleDeg,
                                        double unitWeightKnM3,
                                        double contributedThicknessM,
                                        boolean participating) {
    }

    /** 折算得到的等效单层指标。 */
    public record EquivalentSoilView(double cohesionKpa,
                                     double frictionAngleDeg,
                                     double unitWeightKnM3) {
    }

    public static LayeredBearingResultResponse from(LayeredBearingResult r,
                                                    String source,
                                                    String profileName) {
        List<LayerContributionView> contributionViews = r.contributions().stream()
                .map(c -> {
                    SoilLayer l = c.layer();
                    return new LayerContributionView(
                            c.layerIndex(),
                            l.topDepthM(),
                            l.bottomDepthM(),
                            l.thicknessM(),
                            l.cohesionKpa(),
                            l.frictionAngleDeg(),
                            l.unitWeightKnM3(),
                            c.contributedThicknessM(),
                            c.participating());
                })
                .toList();

        double actual = r.actualWindowLengthM();
        BearingResult bearing = r.bearingResult();
        return new LayeredBearingResultResponse(
                source,
                profileName,
                new ReductionWindowView(r.window().topDepthM(),
                        r.window().bottomDepthM(), r.window().lengthM()),
                r.truncated(),
                actual,
                contributionViews,
                new EquivalentSoilView(
                        r.equivalentSoil().cohesionKpa(),
                        r.equivalentSoil().frictionAngleDeg(),
                        r.equivalentSoil().unitWeightKnM3()),
                // 内嵌的单层核算结果来自折算等效指标，而非某份单层档：
                // source 显式标 EQUIVALENT，与外层 PROFILE/INLINE 区分。
                BearingResultResponse.from(bearing, "EQUIVALENT", null));
    }
}
