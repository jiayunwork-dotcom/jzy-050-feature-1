package com.geotech.bearing.layered.calc;

import com.geotech.bearing.layered.domain.LayerContribution;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.domain.SoilParameters;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分层指标 → 一组等效（单层）指标的折算，独立成类，与承载力因子计算内核互不干扰。
 *
 * <p>给定折算窗口内各层的实际厚度贡献 tᵢ：</p>
 * <ul>
 *   <li><b>黏聚力</b>：直接厚度加权算术平均
 *       <pre>c_eq = Σ(tᵢ·cᵢ) / Σtᵢ</pre></li>
 *   <li><b>重度</b>：直接厚度加权算术平均
 *       <pre>γ_eq = Σ(tᵢ·γᵢ) / Σtᵢ</pre></li>
 *   <li><b>内摩擦角：非线性折算，不许对角度直接取算术平均</b>。
 *       角度是非线性量，直接厚度加权平均后代入承载力因子公式会失真。
 *       必须先把每层角度换算成正切，对正切做厚度加权平均，再反算回角度：
 *       <pre>tan(φ_eq) = Σ(tᵢ·tan φᵢ) / Σtᵢ
 *φ_eq = atan(该加权正切)</pre>
 *       最后让 φ_eq 走既有的承载力因子计算（与单层参数同一条路径）。</li>
 * </ul>
 *
 * <p>退化：如果窗口内只有一层参与（例如埋深处所在那层单独就填满了整个窗口），
 * 等效结果必须严格等于这一层自己的三个指标 —— 单参与层时直接返回该层指标，
 * 不经三角运算，避免任何浮点往返噪声。</p>
 */
@Component
public class EquivalentParametersReducer {

    private static final double THICKNESS_EPS = 1e-12;

    /**
     * @param contributions 窗口切分得到的各层贡献（允许包含 0 贡献层，会被忽略）
     * @return 等效单层指标
     */
    public SoilParameters reduce(List<LayerContribution> contributions) {
        List<LayerContribution> participants = contributions.stream()
                .filter(LayerContribution::participating)
                .toList();
        if (participants.isEmpty()) {
            throw new IllegalStateException(
                    "折算窗口内没有任何参与层，无法折算等效指标（调用方应先拦截此情形）。");
        }

        // 退化：单一参与层填满窗口（或窗口被剖面底面截断到只剩一层），
        // 等效值严格等于该层自身三个指标。
        if (participants.size() == 1) {
            SoilLayer only = participants.get(0).layer();
            return new SoilParameters(only.cohesionKpa(),
                    only.frictionAngleDeg(), only.unitWeightKnM3());
        }

        double totalThickness = 0.0;
        double weightedCohesion = 0.0;
        double weightedUnitWeight = 0.0;
        double weightedTanPhi = 0.0;

        for (LayerContribution contribution : participants) {
            SoilLayer layer = contribution.layer();
            double t = contribution.contributedThicknessM();
            totalThickness += t;
            weightedCohesion += t * layer.cohesionKpa();
            weightedUnitWeight += t * layer.unitWeightKnM3();
            // 关键：对 tan(φ) 加权，而不是对 φ 本身加权。
            weightedTanPhi += t * Math.tan(Math.toRadians(layer.frictionAngleDeg()));
        }

        double cohesionEq = weightedCohesion / totalThickness;
        double unitWeightEq = weightedUnitWeight / totalThickness;
        double tanPhiEq = weightedTanPhi / totalThickness;
        // atan 主值域为 [0°,90°)（各层 φ∈[0,90)，加权正切非负），天然落在合法角度区间。
        double phiEq = Math.toDegrees(Math.atan(tanPhiEq));

        return new SoilParameters(cohesionEq, phiEq, unitWeightEq);
    }

    /**
     * 仅供对账/测试：若（错误地）对内摩擦角直接做厚度加权算术平均会得到的角度。
     *
 * <p>本方法<b>不参与正式折算</b>，存在的目的是把「正确的非线性折算」与
 * 「错误的角度算术平均」在同一组分层数据上的差异显式摆出来，钉住两者必须给出不同结果。</p>
     */
    public double wrongArithmeticMeanFrictionAngle(List<LayerContribution> contributions) {
        double totalThickness = 0.0;
        double weightedPhi = 0.0;
        for (LayerContribution contribution : contributions) {
            if (!contribution.participating()) {
                continue;
            }
            double t = contribution.contributedThicknessM();
            totalThickness += t;
            weightedPhi += t * contribution.layer().frictionAngleDeg();
        }
        if (totalThickness <= THICKNESS_EPS) {
            throw new IllegalStateException("窗口内没有参与层，无法计算对照用算术平均角度。");
        }
        return weightedPhi / totalThickness;
    }
}
