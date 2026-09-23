package com.geotech.bearing.layered.domain;

/**
 * 分层折算各环节共用的深度容差（米）。深度相等判定（层间接续、窗口贴边界）
 * 统一用这一个容差，避免 2.0000000000000004 一类浮点噪声被误判成间隙/重叠。
 */
final class LayeredDomainConstants {

    private LayeredDomainConstants() {
    }

    static final double DEPTH_EPS = 1e-9;
}
