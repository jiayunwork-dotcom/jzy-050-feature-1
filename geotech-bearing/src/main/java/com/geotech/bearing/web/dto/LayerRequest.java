package com.geotech.bearing.web.dto;

/**
 * 分层登记/临时核算中的一层原始输入：给的是<b>层厚</b>与该层三个指标。
 *
 * <p>层序即数组顺序（自上而下）；第一层从地表起算，后续层必须逐层相接，
 * 由服务端累加层厚得到绝对深度并做结构校验。</p>
 */
public record LayerRequest(Double thicknessM,
                           Double cohesionKpa,
                           Double frictionAngleDeg,
                           Double unitWeightKnM3) {
}
