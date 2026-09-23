package com.geotech.bearing.layered.domain;

/**
 * 分层剖面中的一层 —— 以「绝对深度区间」描述（单位 m）。
 *
 * <p>登记接口每层给的是层厚，落库/进入校验前由 {@link LayeredProfileAssembler}
 * 自上而下累加层厚得到本对象的顶面/底面深度。校验与折算窗口切分都在绝对深度上进行，
 * 这样「留空 / 重叠 / 第一层不贴地表」才有地方能被真正检查出来。</p>
 *
 * @param topDepthM        本层顶面深度（第一层必须为 0）
 * @param bottomDepthM     本层底面深度（必须大于顶面深度）
 * @param cohesionKpa      黏聚力 c，kPa
 * @param frictionAngleDeg 内摩擦角 φ，度，合法区间 [0, 90)
 * @param unitWeightKnM3   重度 γ，kN/m³
 */
public record SoilLayer(double topDepthM,
                        double bottomDepthM,
                        double cohesionKpa,
                        double frictionAngleDeg,
                        double unitWeightKnM3) {

    /** 本层厚度 = 底面 − 顶面，m。 */
    public double thicknessM() {
        return bottomDepthM - topDepthM;
    }
}
