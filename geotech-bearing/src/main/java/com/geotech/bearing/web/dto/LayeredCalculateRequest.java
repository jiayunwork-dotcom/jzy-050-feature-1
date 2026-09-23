package com.geotech.bearing.web.dto;

import java.util.List;

/**
 * 一次分层剖面核算请求：layeredProfileName 与 layers 二选一
 * （点名已登记分层剖面，或临时给出整组分层数据）。
 */
public record LayeredCalculateRequest(String layeredProfileName,
                                      List<SoilLayerRequest> layers,
                                      Double widthM,
                                      Double depthM,
                                      String shape) {
}
