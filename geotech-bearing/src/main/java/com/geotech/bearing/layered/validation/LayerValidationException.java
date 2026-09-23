package com.geotech.bearing.layered.validation;

import com.geotech.bearing.validation.InvalidInputException;

/**
 * 分层剖面结构或某一层指标不合法时抛出。
 *
 * <p>继承既有 {@link InvalidInputException}，所以仍由统一的结构化错误通道返回 400；
 * 额外携带 {@link #layerIndex}（人类展示用的层号，<b>从 1 起</b>；不属于某一层时为 -1），
 * 让调用方明确知道是「第几层」出了问题，而不是拿到一句模糊报错。</p>
 */
public class LayerValidationException extends InvalidInputException {

    /** 不隶属于某一层的结构性错误（如层数为 0、第一层不贴地表的层号取 1）使用 -1。 */
    public static final int NO_LAYER = -1;

    private final int layerIndex;

    public LayerValidationException(String reason, String message, int layerIndex) {
        super(reason, message);
        this.layerIndex = layerIndex;
    }

    /** 机器可读层号：0 基；非分层级错误为 -1。 */
    public int getLayerIndexZeroBased() {
        return layerIndex == NO_LAYER ? NO_LAYER : layerIndex - 1;
    }

    /** 人类可读层号：1 基；非分层级错误为 -1。 */
    public int getLayerIndex() {
        return layerIndex;
    }
}
