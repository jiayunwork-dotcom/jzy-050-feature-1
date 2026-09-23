package com.geotech.bearing.domain;

import java.util.List;

/**
 * 分层剖面折算的完整过程与结果：窗口、各层贡献、折算出的等效指标。
 *
 * <p>折算规则：等效黏聚力与等效重度按各层参与厚度做加权算术平均；
 * 等效内摩擦角先把每层 φ 换算成正切值、按厚度加权平均正切值、再反算回角度
 * （φ 是非线性量，直接对角度取算术平均代入承载力因子公式会失真）。</p>
 *
 * @param windowTopM       折算窗口上界（= 埋深 Df），单位 m
 * @param windowBottomM    折算窗口下界（= Df + B/2），单位 m
 * @param contributions    窗口内各层贡献，按深度顺序
 * @param equivalentSoil   折算出的等效土层指标，与单层参数档走同一条计算路径
 */
public record LayeredReduction(double windowTopM,
                               double windowBottomM,
                               List<LayerContribution> contributions,
                               SoilParameters equivalentSoil) {

    public LayeredReduction {
        contributions = List.copyOf(contributions);
    }

    /** 各层贡献厚度之和；剖面底界高于窗口下界时该值小于窗口高度，便于人工核对截断。 */
    public double totalContributingThicknessM() {
        return contributions.stream()
                .mapToDouble(LayerContribution::contributingThicknessM)
                .sum();
    }
}
