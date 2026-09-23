package com.geotech.bearing.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一结构化错误响应。
 *
 * @param timestamp  发生时间
 * @param status     HTTP 状态码
 * @param error      机器可读原因码（如 WIDTH_NOT_POSITIVE、PROFILE_NOT_FOUND）
 * @param message    中文人类说明
 * @param path       请求路径
 * @param layerIndex 仅分层剖面错误出现：出问题的层号（0 基，与数组下标一致）；
 *                   非分层级错误或单层路径不输出该字段
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp,
                            int status,
                            String error,
                            String message,
                            String path,
                            Integer layerIndex) {

    /** 既有单层路径用的构造：不带层号。 */
    public ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
        this(timestamp, status, error, message, path, null);
    }
}
