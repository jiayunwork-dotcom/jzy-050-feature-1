package com.geotech.bearing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分层剖面对外视图：名字、描述、登记时间 + 各层原始（层厚）列表。
 */
public record LayeredProfileResponse(String name,
                                     String description,
                                     Instant createdAt,
                                     List<LayerView> layers) {

    /** 单层视图：层序、层厚与该层三个指标（与登记输入一致，顶/底深度由层序+层厚推出）。 */
    public record LayerView(int layerIndex,
                            double thicknessM,
                            double cohesionKpa,
                            double frictionAngleDeg,
                            double unitWeightKnM3) {
    }
}
