package com.geotech.bearing.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 登记一份分层剖面的请求体。
 *
 * @param name        剖面唯一名（与单层参数档各自独立，允许两边同名互不影响）
 * @param description 可选描述
 * @param layers      自上而下各层（层厚 + c、φ、γ）
 */
public record RegisterLayeredProfileRequest(
        @NotBlank(message = "分层剖面 name 不能为空")
        String name,
        String description,
        List<LayerRequest> layers) {
}
