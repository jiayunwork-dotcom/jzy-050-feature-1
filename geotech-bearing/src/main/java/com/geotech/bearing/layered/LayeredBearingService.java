package com.geotech.bearing.layered;

import com.geotech.bearing.calculation.TerzaghiBearingCalculator;
import com.geotech.bearing.domain.BearingResult;
import com.geotech.bearing.domain.ContributionWindow;
import com.geotech.bearing.domain.FoundationGeometry;
import com.geotech.bearing.domain.LayeredBearingResult;
import com.geotech.bearing.domain.LayeredReduction;
import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.layeredprofile.LayeredProfileService;
import com.geotech.bearing.validation.InputValidator;
import com.geotech.bearing.validation.InvalidInputException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分层剖面核算编排：解析「点名分层剖面 / 临时分层数组」两种来源，
 * 先校验、再折算、最后把等效指标交给现有单层计算内核。
 *
 * <p>调用顺序（每一步不通过都不会进入下一步）：</p>
 * <ol>
 *   <li>分层结构校验（贴地表 / 连续 / 正层厚）与每层物理校验；</li>
 *   <li>基础几何校验（宽度、埋深、形状），与单层同款判据；</li>
 *   <li>确定折算窗口 [Df, Df+B/2] 并切出各层贡献；</li>
 *   <li>非线性折算出等效 (c, φ, γ)；</li>
 *   <li>等效指标走与单层参数完全相同的 {@link TerzaghiBearingCalculator} 计算路径。</li>
 * </ol>
 */
@Service
public class LayeredBearingService {

    private final LayeredProfileService layeredProfileService;
    private final LayeredProfileValidator layeredValidator;
    private final LayerContributionResolver contributionResolver;
    private final EquivalentSoilReducer equivalentReducer;
    private final TerzaghiBearingCalculator bearingCalculator;
    private final InputValidator validator;

    public LayeredBearingService(LayeredProfileService layeredProfileService,
                                 LayeredProfileValidator layeredValidator,
                                 LayerContributionResolver contributionResolver,
                                 EquivalentSoilReducer equivalentReducer,
                                 TerzaghiBearingCalculator bearingCalculator,
                                 InputValidator validator) {
        this.layeredProfileService = layeredProfileService;
        this.layeredValidator = layeredValidator;
        this.contributionResolver = contributionResolver;
        this.equivalentReducer = equivalentReducer;
        this.bearingCalculator = bearingCalculator;
        this.validator = validator;
    }

    /**
     * 对一份分层剖面做一次完整核算。
     *
     * @param layeredProfileName 已登记分层剖面名；非空时以该剖面为准
     * @param inlineLayers       临时给出的分层数组；layeredProfileName 为空时必须非空
     * @param widthM             基础宽度 B，m
     * @param depthM             埋深 Df，m
     * @param shape              平面形状（null/空 按条形 STRIP）
     */
    public LayeredBearingResult calculate(String layeredProfileName,
                                          List<SoilLayer> inlineLayers,
                                          double widthM,
                                          double depthM,
                                          String shape) {
        List<SoilLayer> layers = resolveLayers(layeredProfileName, inlineLayers);
        FoundationGeometry geometry =
                new FoundationGeometry(widthM, depthM, validator.normalizeShape(shape));

        // 先校验（结构 + 每层物理 + 几何），全部通过才允许进入折算。
        layeredValidator.validate(layers);
        validator.validate(geometry);

        // 折算窗口 + 非线性折算，产出等效单层指标。
        ContributionWindow window = contributionResolver.resolve(layers, depthM, widthM);
        LayeredReduction reduction = equivalentReducer.reduce(window);

        // 等效指标与单层参数走同一条计算路径（因子、黏土极限、三项叠加、形状修正）。
        BearingResult bearingResult =
                bearingCalculator.calculate(reduction.equivalentSoil(), geometry);
        return new LayeredBearingResult(reduction, bearingResult);
    }

    private List<SoilLayer> resolveLayers(String layeredProfileName, List<SoilLayer> inlineLayers) {
        boolean named = layeredProfileName != null && !layeredProfileName.isBlank();
        if (named) {
            // 点名未登记剖面会抛 LayeredProfileNotFoundException，绝不拿默认值凑结果。
            return layeredProfileService.requireLayers(layeredProfileName.trim());
        }
        if (inlineLayers == null || inlineLayers.isEmpty()) {
            throw new InvalidInputException("LAYERS_MISSING",
                    "必须点名一个已登记分层剖面（layeredProfileName），或在请求体内临时给出分层数组 layers。");
        }
        return inlineLayers;
    }
}
