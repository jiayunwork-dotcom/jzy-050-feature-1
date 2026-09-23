package com.geotech.bearing.layered.validation;

import com.geotech.bearing.domain.SoilParameters;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.validation.InputValidator;
import com.geotech.bearing.validation.InvalidInputException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分层剖面结构校验 —— 在「真正进入折算之前」独立完成，与单层计算内核互不干扰。
 *
 * <p>本类只负责分层特有的结构判据：</p>
 * <ol>
 *   <li>至少有一层；</li>
 *   <li>每层厚度必须为正（顶/底深度还须是有限值）；</li>
 *   <li>第一层必须贴着地表（顶面深度为 0）；</li>
 *   <li>自上而下每层顶面必须与上一层底面<b>正好相接</b>：
 *       有间隙（留空）或两层重叠都拒绝；</li>
 *   <li>每层各自的 c、φ、γ 仍走既有 {@link InputValidator} ——
 *       黏聚力非负、φ∈[0,90)（90° 拦截）、重度为正这些已钉死的判据一条不改，
 *       这里只在其外面包一层「出错的是第几层」的信息。</li>
 * </ol>
 *
 * <p>任何一条不成立都抛 {@link LayerValidationException}，指明层号（从 1 起），
 * 绝不静默跳过继续往下折算。</p>
 */
@Component
public class LayeredProfileValidator {

    /** 深度相接判定容差，米，与折算域其他环节一致。 */
    private static final double DEPTH_EPS = 1e-9;

    private final InputValidator inputValidator;

    public LayeredProfileValidator(InputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    /**
     * 校验一份按深度顺序排列的分层剖面。
     *
     * @param layers 绝对深度区间描述的各层（第一层顶面应为 0）
     * @throws LayerValidationException 任一结构/指标不合法
     */
    public void validate(List<SoilLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new LayerValidationException("LAYERED_PROFILE_EMPTY",
                    "分层剖面必须至少包含一层：layers 为空。", LayerValidationException.NO_LAYER);
        }

        for (int i = 0; i < layers.size(); i++) {
            validateLayer(layers.get(i), i);
        }

        // 第一层必须贴着地表：从深度 0 起算。
        SoilLayer first = layers.get(0);
        if (Math.abs(first.topDepthM()) > DEPTH_EPS) {
            throw new LayerValidationException("LAYER_ONE_NOT_AT_SURFACE",
                    "第 1 层必须从地表（深度 0）起算，实际顶面深度="
                            + first.topDepthM() + " m。", 1);
        }

        // 相邻两层：顶面必须正好接上一层底面，不许留空、不许重叠。
        for (int i = 1; i < layers.size(); i++) {
            SoilLayer previous = layers.get(i - 1);
            SoilLayer current = layers.get(i);
            double gap = current.topDepthM() - previous.bottomDepthM();
            if (gap > DEPTH_EPS) {
                throw new LayerValidationException("LAYER_GAP",
                        "第 " + (i + 1) + " 层顶面（深度 " + current.topDepthM()
                                + " m）没有接住上一层底面（深度 " + previous.bottomDepthM()
                                + " m），中间留有 " + gap + " m 空隙，不允许。",
                        i + 1);
            }
            if (gap < -DEPTH_EPS) {
                throw new LayerValidationException("LAYER_OVERLAP",
                        "第 " + (i + 1) + " 层顶面（深度 " + current.topDepthM()
                                + " m）压进了上一层底面（深度 " + previous.bottomDepthM()
                                + " m），两层重叠 " + (-gap) + " m，不允许。",
                        i + 1);
            }
        }
    }

    private void validateLayer(SoilLayer layer, int index) {
        int layerNo = index + 1;
        if (layer == null) {
            throw new LayerValidationException("LAYER_NULL",
                    "第 " + layerNo + " 层为空：每层都必须完整给出厚度与三个指标。", layerNo);
        }
        if (Double.isNaN(layer.topDepthM()) || Double.isNaN(layer.bottomDepthM())
                || Double.isInfinite(layer.topDepthM()) || Double.isInfinite(layer.bottomDepthM())) {
            throw new LayerValidationException("LAYER_DEPTH_INVALID",
                    "第 " + layerNo + " 层的顶/底面深度必须是有限数值。", layerNo);
        }
        if (layer.bottomDepthM() <= layer.topDepthM() + DEPTH_EPS) {
            throw new LayerValidationException("LAYER_THICKNESS_NOT_POSITIVE",
                    "第 " + layerNo + " 层层厚必须为正：顶面=" + layer.topDepthM()
                            + " m，底面=" + layer.bottomDepthM() + " m。", layerNo);
        }

        // 单层指标复用既有校验，判据与单层路径完全一致；只补「第几层」的上下文。
        try {
            inputValidator.validate(new SoilParameters(
                    layer.cohesionKpa(),
                    layer.frictionAngleDeg(),
                    layer.unitWeightKnM3()));
        } catch (InvalidInputException ex) {
            throw new LayerValidationException(ex.getReason(),
                    "第 " + layerNo + " 层：" + ex.getMessage(), layerNo);
        }
    }
}
