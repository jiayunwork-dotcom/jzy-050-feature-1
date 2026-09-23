package com.geotech.bearing.web;

import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.domain.SoilParameters;
import com.geotech.bearing.validation.InvalidInputException;
import com.geotech.bearing.web.dto.InlineSoilRequest;
import com.geotech.bearing.web.dto.SoilLayerRequest;

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
     * 把请求里的分层数组映射为领域层列表（顺序原样保持，结构合法性由
     * {@code LayeredProfileValidator} 在校验阶段把关）。任一层的任一项缺失
     * 都显式报「第几层缺哪项」，层号自 1 起。
     */
    static List<SoilLayer> toSoilLayers(List<SoilLayerRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidInputException("LAYERS_MISSING",
                    "请求体中缺少 layers（分层数组），分层剖面至少需要一层土。");
        }
        List<SoilLayer> layers = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            SoilLayerRequest req = requests.get(i);
            int layerNo = i + 1;
            if (req == null) {
                throw new InvalidInputException("LAYER_MISSING",
                        "第 " + layerNo + " 层缺失（层数据为 null）。");
            }
            layers.add(new SoilLayer(
                    requireLayerField(req.topDepthM(), layerNo, "topDepthM"),
                    requireLayerField(req.thicknessM(), layerNo, "thicknessM"),
                    requireLayerField(req.cohesionKpa(), layerNo, "cohesionKpa"),
                    requireLayerField(req.frictionAngleDeg(), layerNo, "frictionAngleDeg"),
                    requireLayerField(req.unitWeightKnM3(), layerNo, "unitWeightKnM3")));
        }
        return layers;
    }

    private static double requireLayerField(Double value, int layerNo, String field) {
        if (value == null) {
            throw new InvalidInputException("LAYER_FIELD_MISSING",
                    "第 " + layerNo + " 层缺少字段 " + field + "。");
        }
        return value;
    }
}
