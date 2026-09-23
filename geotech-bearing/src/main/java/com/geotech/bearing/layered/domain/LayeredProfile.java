package com.geotech.bearing.layered.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一份已登记的分层剖面（脱离 JPA 会话的领域视图）：
 * 名字、描述、登记时间与自地表起按深度排列的各层。
 */
public record LayeredProfile(String name,
                             String description,
                             Instant createdAt,
                             List<SoilLayer> layers) {
}
