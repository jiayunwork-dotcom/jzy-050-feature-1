package com.geotech.bearing.web;

import com.geotech.bearing.domain.SoilParameters;
import com.geotech.bearing.layered.domain.LayeredProfileAssembler;
import com.geotech.bearing.layered.validation.LayerValidationException;
import com.geotech.bearing.validation.InvalidInputException;
import com.geotech.bearing.web.dto.InlineSoilRequest;
import com.geotech.bearing.web.dto.LayerRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Web 层临时参数映射为领域值对象；字段缺失显式报「缺少」而不是任其 NPE。
 */
final class WebDtoMapper {

    private WebDtoMapper() {
    }

    static SoilParameters toSoilParameters(InlineSoilRequest req) {
        if (req == null) {
            throw new InvalidInputException("SOIL_PARAMETERS_MISSING",
                    "请求体中缺少 soil（全套土层参数）。");
        }
        if (req.cohesionKpa() == null) {
            throw new InvalidInputException("COHESION_MISSING", "缺少黏聚力 cohesionKpa。");
        }
        if (req.frictionAngleDeg() == null) {
            throw new InvalidInputException("FRICTION_ANGLE_MISSING", "缺少内摩擦角 frictionAngleDeg。");
        }
        if (req.unitWeightKnM3() == null) {
            throw new InvalidInputException("UNIT_WEIGHT_MISSING", "缺少土的重度 unitWeightKnM3。");
        }
        return new SoilParameters(req.cohesionKpa(), req.frictionAngleDeg(), req.unitWeightKnM3());
    }

    /** 几何字段缺失时给出明确的结构化错误（避免 NPE）。 */
    static double require(Double value, String field, String reason) {
        if (value == null || Double.isNaN(value)) {
            throw new InvalidInputException(reason, "缺少参数：" + field + "。");
        }
        return value;
    }

    /**
     * 分层请求映射：逐层检查字段齐备，任何缺失都带上「第几层」，
     * 结构是否合法（贴地表/相接/层厚为正/指标范围）交给 LayeredProfileValidator。
     */
    static List<LayeredProfileAssembler.LayerInput> toLayerInputs(List<LayerRequest> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new LayerValidationException("LAYERED_PROFILE_EMPTY",
                    "缺少分层数据：layers 必须至少包含一层（自上而下给出层厚与各层指标）。",
                    LayerValidationException.NO_LAYER);
        }
        List<LayeredProfileAssembler.LayerInput> inputs = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            int layerNo = i + 1;
            LayerRequest req = layers.get(i);
            if (req == null) {
                throw new LayerValidationException("LAYER_NULL",
                        "第 " + layerNo + " 层为空：每层都必须完整给出厚度与三个指标。", layerNo);
            }
            double thickness = requireLayerField(req.thicknessM(), "thicknessM",
                    "LAYER_THICKNESS_MISSING", layerNo);
            double cohesion = requireLayerField(req.cohesionKpa(), "cohesionKpa",
                    "LAYER_COHESION_MISSING", layerNo);
            double phi = requireLayerField(req.frictionAngleDeg(), "frictionAngleDeg",
                    "LAYER_FRICTION_ANGLE_MISSING", layerNo);
            double gamma = requireLayerField(req.unitWeightKnM3(), "unitWeightKnM3",
                    "LAYER_UNIT_WEIGHT_MISSING", layerNo);
            inputs.add(new LayeredProfileAssembler.LayerInput(thickness, cohesion, phi, gamma));
        }
        return inputs;
    }

    private static double requireLayerField(Double value, String field, String reason, int layerNo) {
        if (value == null || Double.isNaN(value)) {
            throw new LayerValidationException(reason,
                    "第 " + layerNo + " 层缺少参数：" + field + "。", layerNo);
        }
        return value;
    }
}
