package com.geotech.bearing.layered.domain;

/**
 * 某一层在折算窗口内的实际贡献。
 *
 * @param layerIndex              该层在剖面中的序号（从 0 起，与登记顺序一致）
 * @param layer                   该层的绝对深度区间与指标
 * @param contributedThicknessM   该层落在窗口 [Df, Df+B/2] 内的实际厚度，m；
 *                                完全不在窗口内为 0，部分落入只计落入的那一段
 */
public record LayerContribution(int layerIndex,
                                SoilLayer layer,
                                double contributedThicknessM) {

    /** 厚度贡献为正才算参与折算。 */
    public boolean participating() {
        return contributedThicknessM > LayeredDomainConstants.DEPTH_EPS;
    }
}
