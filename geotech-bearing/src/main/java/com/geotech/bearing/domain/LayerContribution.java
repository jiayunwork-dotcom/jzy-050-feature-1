package com.geotech.bearing.domain;

/**
 * 折算窗口内某一层的实际参与情况：哪一层、在窗口内占到多少厚度。
 *
 * <p>某层只有一部分落进窗口时，{@code contributingThicknessM} 只计落在窗口内的那段；
 * 完全在窗口之外的层不会出现在贡献列表里。</p>
 *
 * @param layerIndex              层号（自 1 起，与剖面登记顺序一致，便于人对照报告）
 * @param layer                   该层原始数据（顶底界与指标）
 * @param contributingThicknessM  该层落在折算窗口内的厚度，单位 m，恒为正
 */
public record LayerContribution(int layerIndex,
                                SoilLayer layer,
                                double contributingThicknessM) {
}
