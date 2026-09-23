package com.geotech.bearing.domain;

/**
 * 分层剖面中的一层土：顶面深度 + 层厚 + 本层抗剪与重度指标。
 *
 * <p>一份分层剖面由若干层按深度顺序拼接而成：第一层顶面必须贴着地表（深度 0），
 * 往下每一层的顶面必须正好接上一层的底面，层厚必须为正——这些结构约束由
 * {@code layered} 包的校验器在折算之前把关，本值对象只承载数据。</p>
 *
 * @param topDepthM        本层顶面深度（自地表起算），单位 m
 * @param thicknessM       本层层厚，单位 m，必须为正
 * @param cohesionKpa      本层黏聚力 c，单位 kPa
 * @param frictionAngleDeg 本层内摩擦角 φ，单位「度」，合法区间 [0, 90)
 * @param unitWeightKnM3   本层重度 γ，单位 kN/m³
 */
public record SoilLayer(double topDepthM,
                        double thicknessM,
                        double cohesionKpa,
                        double frictionAngleDeg,
                        double unitWeightKnM3) {

    /** 本层底面深度 = 顶面深度 + 层厚。 */
    public double bottomDepthM() {
        return topDepthM + thicknessM;
    }

    /** 抽出本层的抗剪与重度指标，与单层参数档同款的值对象。 */
    public SoilParameters toSoilParameters() {
        return new SoilParameters(cohesionKpa, frictionAngleDeg, unitWeightKnM3);
    }
}
