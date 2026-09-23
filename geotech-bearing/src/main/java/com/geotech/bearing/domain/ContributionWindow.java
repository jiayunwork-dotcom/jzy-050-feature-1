package com.geotech.bearing.domain;

import java.util.List;

/**
 * 一次核算的折算窗口及其切出的各层贡献。
 *
 * <p>窗口定义为 {@code [埋深 Df, Df + B/2]}：起点在基础埋深处（不是地表），
 * 向下取半个基础宽度。埋深落在剖面第几层内部，窗口就从那一层内部切开算起；
 * 窗口下界探到剖面最深处以下时，贡献列表只含实际能取到的层，不虚构补层。</p>
 *
 * @param topM          窗口上界（= 基础埋深 Df），单位 m
 * @param bottomM       窗口下界（= Df + B/2），单位 m
 * @param contributions 窗口内各层的贡献（仅含实际参与折算的层，按深度顺序）
 */
public record ContributionWindow(double topM,
                                 double bottomM,
                                 List<LayerContribution> contributions) {

    public ContributionWindow {
        contributions = List.copyOf(contributions);
    }
}
