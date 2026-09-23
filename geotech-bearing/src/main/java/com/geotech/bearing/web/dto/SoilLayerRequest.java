package com.geotech.bearing.web.dto;

/**
 * 分层剖面中一层的请求表示。字段使用包装类型，
 * 缺字段时由映射逻辑显式报「缺少哪一层哪一项」而不是 NPE。
 */
public record SoilLayerRequest(Double topDepthM,
                               Double thicknessM,
                               Double cohesionKpa,
                               Double frictionAngleDeg,
                               Double unitWeightKnM3) {
}
