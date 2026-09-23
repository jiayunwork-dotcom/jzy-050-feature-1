package com.geotech.bearing.web.dto;

import java.util.List;

/**
 * 分层剖面核算请求：{@code layeredProfileName} 与 {@code layers} 二选一
 * （点名已登记分层剖面，或临时给出整组分层数据）。
 *
 * <p>与既有单层核算请求体互不干扰：既不填 {@code layeredProfileName} 也不填 {@code layers}
 * 时，分层接口明确报「缺少分层数据」，不会去碰单层档。</p>
 */
public record LayeredCalculateRequest(String layeredProfileName,
                                      List<LayerRequest> layers,
                                      Double widthM,
                                      Double depthM,
                                      String shape) {
}
