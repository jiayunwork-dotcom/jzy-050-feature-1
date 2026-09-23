package com.geotech.bearing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分层剖面对外视图：剖面名、说明与原始分层列表（按深度顺序）。
 */
public record LayeredProfileResponse(String name,
                                     String description,
                                     Instant createdAt,
                                     List<LayerView> layers) {

    public record LayerView(double topDepthM,
                            double thicknessM,
                            double cohesionKpa,
                            double frictionAngleDeg,
                            double unitWeightKnM3) {
    }
}
