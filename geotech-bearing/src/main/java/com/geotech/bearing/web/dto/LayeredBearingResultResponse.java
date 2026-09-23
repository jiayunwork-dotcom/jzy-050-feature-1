package com.geotech.bearing.web.dto;

import com.geotech.bearing.domain.LayerContribution;
import com.geotech.bearing.domain.LayeredBearingResult;
import com.geotech.bearing.domain.LayeredReduction;

import java.util.List;

/**
 * 分层剖面核算结果对外视图：既给出最终承载力因子与 qu，
 * 也把折算过程摊开（窗口上下界、每层贡献厚度、等效指标），供人工核对折算这一步。
 *
 * <p>因子、形状系数、三项分项视图直接复用单层核算的同款结构——
 * 等效指标与单层参数走的是同一条计算路径，结果结构自然一致。</p>
 */
public record LayeredBearingResultResponse(
        String source,
        String layeredProfileName,
        BearingResultResponse.GeometryView geometry,
        WindowView window,
        List<ContributionView> contributions,
        BearingResultResponse.SoilView equivalentSoil,
        BearingResultResponse.FactorsView factors,
        BearingResultResponse.ShapeFactorsView shapeFactors,
        BearingResultResponse.TermsView terms) {

    /** 折算窗口：[埋深 Df, Df + B/2]；总贡献厚度小于窗口高度说明剖面底界截断了窗口。 */
    public record WindowView(double topDepthM,
                             double bottomDepthM,
                             double totalContributingThicknessM) {
    }

    /** 窗口内某一层的参与情况：层号、该层顶底界、落在窗口内的厚度。 */
    public record ContributionView(int layerIndex,
                                   double layerTopDepthM,
                                   double layerBottomDepthM,
                                   double contributingThicknessM) {
        static ContributionView of(LayerContribution c) {
            return new ContributionView(
                    c.layerIndex(),
                    c.layer().topDepthM(),
                    c.layer().bottomDepthM(),
                    c.contributingThicknessM());
        }
    }

    public static LayeredBearingResultResponse from(LayeredBearingResult result,
                                                    String source,
                                                    String layeredProfileName) {
        LayeredReduction r = result.reduction();
        return new LayeredBearingResultResponse(
                source,
                layeredProfileName,
                new BearingResultResponse.GeometryView(
                        result.bearingResult().geometry().widthM(),
                        result.bearingResult().geometry().depthM(),
                        result.bearingResult().geometry().shape().name()),
                new WindowView(r.windowTopM(), r.windowBottomM(), r.totalContributingThicknessM()),
                r.contributions().stream().map(ContributionView::of).toList(),
                new BearingResultResponse.SoilView(
                        r.equivalentSoil().cohesionKpa(),
                        r.equivalentSoil().frictionAngleDeg(),
                        r.equivalentSoil().unitWeightKnM3()),
                BearingResultResponse.FactorsView.of(result.bearingResult().factors()),
                BearingResultResponse.ShapeFactorsView.of(result.bearingResult().shapeFactors()),
                BearingResultResponse.TermsView.of(result.bearingResult().terms()));
    }
}
