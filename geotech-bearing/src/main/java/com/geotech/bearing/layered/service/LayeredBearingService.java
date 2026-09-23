package com.geotech.bearing.layered.service;

import com.geotech.bearing.calculation.TerzaghiBearingCalculator;
import com.geotech.bearing.domain.BearingResult;
import com.geotech.bearing.domain.FoundationGeometry;
import com.geotech.bearing.domain.FoundationShape;
import com.geotech.bearing.domain.SoilParameters;
import com.geotech.bearing.layered.calc.EquivalentParametersReducer;
import com.geotech.bearing.layered.calc.LayeredBearingResult;
import com.geotech.bearing.layered.calc.ReductionWindowResolver;
import com.geotech.bearing.layered.domain.LayeredProfile;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.layered.profile.LayeredProfileService;
import com.geotech.bearing.layered.validation.LayeredProfileValidator;
import com.geotech.bearing.validation.InputValidator;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分层剖面核算编排：点名分层剖面 / 临时给出整组分层数据两种来源，
 * 先做结构校验，再确定折算窗口，再做非线性折算，最后把等效指标交给
 * <b>既有单层计算路径</b>（{@link InputValidator} + {@link TerzaghiBearingCalculator}）。
 *
 * <p>本类只负责调用顺序，不重写因子计算、黏土极限退化、形状修正、三项叠加任何一段。</p>
 */
@Service
public class LayeredBearingService {

    private final LayeredProfileService layeredProfileService;
    private final LayeredProfileValidator layeredValidator;
    private final ReductionWindowResolver windowResolver;
    private final EquivalentParametersReducer reducer;
    private final TerzaghiBearingCalculator bearingCalculator;
    private final InputValidator validator;

    public LayeredBearingService(LayeredProfileService layeredProfileService,
                                 LayeredProfileValidator layeredValidator,
                                 ReductionWindowResolver windowResolver,
                                 EquivalentParametersReducer reducer,
                                 TerzaghiBearingCalculator bearingCalculator,
                                 InputValidator validator) {
        this.layeredProfileService = layeredProfileService;
        this.layeredValidator = layeredValidator;
        this.windowResolver = windowResolver;
        this.reducer = reducer;
        this.bearingCalculator = bearingCalculator;
        this.validator = validator;
    }

    /**
     * 点名一份已登记分层剖面做核算。
     */
    public LayeredBearingResult calculateByName(String profileName,
                                                double widthM,
                                                double depthM,
                                                String shape) {
        LayeredProfile profile = layeredProfileService.requireProfile(profileName.trim());
        return doCalculate(profile.layers(), widthM, depthM, shape);
    }

    /**
     * 用请求中临时给出的整组分层数据核算（不必先登记）。
     */
    public LayeredBearingResult calculateInline(List<SoilLayer> layers,
                                                double widthM,
                                                double depthM,
                                                String shape) {
        return doCalculate(layers, widthM, depthM, shape);
    }

    private LayeredBearingResult doCalculate(List<SoilLayer> layers,
                                             double widthM,
                                             double depthM,
                                             String shape) {
        FoundationShape foundationShape = validator.normalizeShape(shape);
        FoundationGeometry geometry = new FoundationGeometry(widthM, depthM, foundationShape);

        // 先校验分层结构，再校验基础几何 —— 全部在进入折算 / 因子计算之前。
        layeredValidator.validate(layers);
        validator.validate(geometry);

        // 第一步：确定折算窗口 [Df, Df+B/2]，切出每层实际贡献厚度。
        ReductionWindowResolver.ResolvedWindow resolved =
                windowResolver.resolve(layers, depthM, widthM);

        // 第二步：非线性折算（c、γ 厚度加权；φ 走 tan 加权再反算）。
        SoilParameters equivalent = reducer.reduce(resolved.contributions());

        // 等效指标与单层参数走同一条计算路径；同一条物理校验（φ 区间等）再兜底一遍。
        validator.validate(equivalent);
        BearingResult bearingResult = bearingCalculator.calculate(equivalent, geometry);

        return new LayeredBearingResult(
                resolved.window(),
                resolved.truncated(),
                resolved.contributions(),
                resolved.actualWindowLengthM(),
                equivalent,
                bearingResult);
    }
}
