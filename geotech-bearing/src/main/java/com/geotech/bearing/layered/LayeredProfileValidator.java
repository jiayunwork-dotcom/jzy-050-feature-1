package com.geotech.bearing.layered;

import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.validation.InputValidator;
import com.geotech.bearing.validation.InvalidInputException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分层剖面的结构校验，在进入任何折算之前执行。
 *
 * <p>挡住的结构问题（每一处都指出是第几层，层号自 1 起）：</p>
 * <ul>
 *   <li>剖面为空（一层都没有）；</li>
 *   <li>第一层不贴地表——顶面深度必须为 0；</li>
 *   <li>层间留空（本层顶面浅于上一层底面）或重叠（本层顶面深于上一层底面）；</li>
 *   <li>层厚不为正数。</li>
 * </ul>
 *
 * <p>结构过关后，每一层的抗剪与重度指标复用 {@link InputValidator} 的单层物理校验
 * （c≥0、0≤φ&lt;90、γ&gt;0），判据与单层参数档完全一致，只是错误说明前附上层号。</p>
 */
@Component
public class LayeredProfileValidator {

    /** 深度比较的浮点容差：|偏差| 在此之内视为「正好接上」，避免浮点噪声误报间隙/重叠。 */
    private static final double DEPTH_TOLERANCE = 1e-9;

    private final InputValidator inputValidator;

    public LayeredProfileValidator(InputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    /**
     * 校验一整份分层剖面（按深度顺序）。任何一处不成立立即抛出，
     * 绝不悄悄跳过问题层继续往下折算。
     */
    public void validate(List<SoilLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new InvalidInputException("LAYERED_PROFILE_EMPTY",
                    "分层剖面至少需要一层土。");
        }
        for (int i = 0; i < layers.size(); i++) {
            SoilLayer layer = layers.get(i);
            int layerNo = i + 1; // 对人展示的层号自 1 起
            if (layer == null) {
                throw new InvalidInputException("LAYER_MISSING",
                        "第 " + layerNo + " 层缺失（层数据为 null）。");
            }
            if (Double.isNaN(layer.topDepthM())) {
                throw new InvalidInputException("LAYER_TOP_DEPTH_INVALID",
                        "第 " + layerNo + " 层顶面深度非法：topDepthM=" + layer.topDepthM() + " m。");
            }
            if (Double.isNaN(layer.thicknessM()) || layer.thicknessM() <= 0.0) {
                throw new InvalidInputException("LAYER_THICKNESS_NOT_POSITIVE",
                        "第 " + layerNo + " 层层厚必须为正：thicknessM="
                                + layer.thicknessM() + " m。");
            }
            if (i == 0) {
                validateFirstLayerAtSurface(layer);
            } else {
                validateContinuity(layers.get(i - 1), layer, layerNo);
            }
            validateLayerPhysics(layer, layerNo);
        }
    }

    /** 第一层必须贴着地表，从深度零开始起算。 */
    private void validateFirstLayerAtSurface(SoilLayer first) {
        if (Math.abs(first.topDepthM()) > DEPTH_TOLERANCE) {
            throw new InvalidInputException("FIRST_LAYER_NOT_AT_SURFACE",
                    "第 1 层必须贴着地表（顶面深度为 0）：实际 topDepthM="
                            + first.topDepthM() + " m。");
        }
    }

    /** 本层顶面必须正好接上一层底面：浅了留空、深了重叠，都不允许。 */
    private void validateContinuity(SoilLayer previous, SoilLayer current, int layerNo) {
        double expectedTop = previous.bottomDepthM();
        double gap = current.topDepthM() - expectedTop;
        if (gap > DEPTH_TOLERANCE) {
            throw new InvalidInputException("LAYER_GAP",
                    "第 " + layerNo + " 层顶面（" + current.topDepthM()
                            + " m）与上一层底面（" + expectedTop + " m）之间留空 "
                            + gap + " m，层间不许有间隙。");
        }
        if (gap < -DEPTH_TOLERANCE) {
            throw new InvalidInputException("LAYER_OVERLAP",
                    "第 " + layerNo + " 层顶面（" + current.topDepthM()
                            + " m）压进上一层（底面 " + expectedTop + " m）之内，重叠 "
                            + (-gap) + " m，层间不许重叠。");
        }
    }

    /** 单层物理校验与单层参数档同款，错误说明前附上层号，指出是哪一层出了问题。 */
    private void validateLayerPhysics(SoilLayer layer, int layerNo) {
        try {
            inputValidator.validate(layer.toSoilParameters());
        } catch (InvalidInputException ex) {
            throw new InvalidInputException(ex.getReason(),
                    "第 " + layerNo + " 层：" + ex.getMessage());
        }
    }
}
