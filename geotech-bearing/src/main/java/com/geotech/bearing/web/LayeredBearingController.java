package com.geotech.bearing.web;

import com.geotech.bearing.layered.calc.LayeredBearingResult;
import com.geotech.bearing.layered.domain.LayeredProfileAssembler;
import com.geotech.bearing.layered.service.LayeredBearingService;
import com.geotech.bearing.validation.InvalidInputException;
import com.geotech.bearing.web.dto.LayeredBearingResultResponse;
import com.geotech.bearing.web.dto.LayeredCalculateRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分层剖面核算接口（与既有单层核算接口并行，路径独立，互不影响）。
 *
 * <ul>
 *   <li>POST /api/layered-bearings/calculate —— 对一份分层剖面（点名或临时给分层数组）
 *       配合基础宽度、埋深、形状做一次核算，响应摊开折算窗口、每层贡献厚度、
 *       等效 c/φ/γ 与最终因子、qu。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/layered-bearings")
public class LayeredBearingController {

    private final LayeredBearingService layeredBearingService;

    public LayeredBearingController(LayeredBearingService layeredBearingService) {
        this.layeredBearingService = layeredBearingService;
    }

    @PostMapping("/calculate")
    public LayeredBearingResultResponse calculate(@RequestBody LayeredCalculateRequest request) {
        double width = WebDtoMapper.require(request.widthM(), "widthM", "WIDTH_MISSING");
        double depth = WebDtoMapper.require(request.depthM(), "depthM", "DEPTH_MISSING");

        boolean named = request.layeredProfileName() != null
                && !request.layeredProfileName().isBlank();

        if (named) {
            if (request.layers() != null && !request.layers().isEmpty()) {
                throw new InvalidInputException("LAYERED_SOURCE_AMBIGUOUS",
                        "layeredProfileName 与 layers 只能二选一：点名已登记分层剖面，"
                                + "或临时给出整组分层数据。");
            }
            String name = request.layeredProfileName().trim();
            LayeredBearingResult result =
                    layeredBearingService.calculateByName(name, width, depth, request.shape());
            return LayeredBearingResultResponse.from(result, "PROFILE", name);
        }

        // 临时分层：未点名时 layers 必填，缺了明确报错，不去碰单层档。
        List<LayeredProfileAssembler.LayerInput> rawLayers =
                WebDtoMapper.toLayerInputs(request.layers());
        List<com.geotech.bearing.layered.domain.SoilLayer> layers =
                LayeredProfileAssembler.assemble(rawLayers);
        LayeredBearingResult result =
                layeredBearingService.calculateInline(layers, width, depth, request.shape());
        return LayeredBearingResultResponse.from(result, "INLINE", null);
    }
}
