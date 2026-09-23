package com.geotech.bearing.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 登记一份分层剖面的请求体：剖面名 + 按深度顺序排列的层列表。
 */
public record RegisterLayeredProfileRequest(
        @NotBlank(message = "分层剖面名 name 不能为空")
        String name,
        String description,
        List<SoilLayerRequest> layers) {
}
