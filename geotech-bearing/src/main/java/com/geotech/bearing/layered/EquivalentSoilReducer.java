package com.geotech.bearing.layered;

import com.geotech.bearing.domain.ContributionWindow;
import com.geotech.bearing.domain.LayerContribution;
import com.geotech.bearing.domain.LayeredReduction;
import com.geotech.bearing.domain.SoilParameters;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 等效指标折算：把窗口内各层贡献折成一组等效的 (c, φ, γ)。
 *
 * <p>折算规则：</p>
 * <ul>
 *   <li>黏聚力、重度：按各层参与折算的厚度做<b>加权算术平均</b>；</li>
 *   <li>内摩擦角：<b>不做</b>角度的算术平均——φ 进入承载力因子公式的方式是非线性的，
 *       直接平均角度会失真。必须先把每层 φ 换算成正切值 tan(φ)，按厚度加权平均这些
 *       正切值，再从加权结果用 atan 反算回等效角度（度）。</li>
 * </ul>
 *
 * <p>退化：窗口内只有一层参与（例如埋深所在层单独填满整个窗口）时，
 * 等效指标直接取该层自身的三个值，不掺任何别的层，也避免 tan→atan 的浮点往返。</p>
 *
 * <p>折算结果随后喂给现有单层计算内核（因子、黏土极限、三项叠加），
 * 本类只做「喂进去之前」的预处理，不重写任何承载力计算。</p>
 */
@Component
public class EquivalentSoilReducer {

    /**
     * 把一个折算窗口内的各层贡献折成等效指标，并组装出完整的折算明细。
     *
     * @param window 折算窗口（上下界 + 各层贡献厚度）
     * @return 折算明细：窗口、各层贡献、等效指标
     */
    public LayeredReduction reduce(ContributionWindow window) {
        List<LayerContribution> contributions = window.contributions();

        if (contributions.size() == 1) {
            // 退化：单独一层填满整个窗口，等效指标就是这一层自己的三个值。
            SoilParameters self = contributions.get(0).layer().toSoilParameters();
            return new LayeredReduction(window.topM(), window.bottomM(), contributions, self);
        }

        double totalThickness = contributions.stream()
                .mapToDouble(LayerContribution::contributingThicknessM)
                .sum();

        double cohesionSum = 0.0;
        double unitWeightSum = 0.0;
        double tanPhiSum = 0.0;
        for (LayerContribution contribution : contributions) {
            double h = contribution.contributingThicknessM();
            cohesionSum += h * contribution.layer().cohesionKpa();
            unitWeightSum += h * contribution.layer().unitWeightKnM3();
            // φ 的非线性折算：对 tan(φ) 做厚度加权，而不是对角度本身加权。
            tanPhiSum += h * Math.tan(Math.toRadians(contribution.layer().frictionAngleDeg()));
        }

        double equivalentCohesion = cohesionSum / totalThickness;
        double equivalentUnitWeight = unitWeightSum / totalThickness;
        double equivalentFrictionAngle =
                Math.toDegrees(Math.atan(tanPhiSum / totalThickness));

        SoilParameters equivalent = new SoilParameters(
                equivalentCohesion, equivalentFrictionAngle, equivalentUnitWeight);
        return new LayeredReduction(window.topM(), window.bottomM(), contributions, equivalent);
    }
}
