package com.geotech.bearing.layered.domain;

/**
 * 等效指标的折算窗口：从埋深处起算、长度为半个基础宽度的深度区间 [Df, Df + B/2]。
 *
 * <p>注意窗口起点是埋深 Df（不是地表 0）；比 Df 更浅的土层一律不参与折算。</p>
 *
 * @param topDepthM    窗口上界（= 基础埋深 Df）
 * @param bottomDepthM 窗口下界（= Df + B/2）
 */
public record ReductionWindow(double topDepthM, double bottomDepthM) {

    /** 窗口名义长度 B/2，m。 */
    public double lengthM() {
        return bottomDepthM - topDepthM;
    }
}
