package com.geotech.bearing.web;

import com.geotech.bearing.domain.LayeredBearingResult;
import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.layered.LayeredBearingService;
import com.geotech.bearing.web.dto.LayeredBearingResultResponse;
import com.geotech.bearing.web.dto.LayeredCalculateRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分层剖面核算接口：与单层核算同挂在 /api/bearings 下，
 * 通过独立路径 calculate-layered 区分——一次请求引用的是分层剖面还是
 * 单层参数档，从路径与请求字段（layeredProfileName/layers 对 profileName/soil）
 * 即可分辨，两边互不干扰。
 */
@RestController
@RequestMapping("/api/bearings")
public class LayeredBearingController {

    private final LayeredBearingService layeredBearingService;

    public LayeredBearingController(LayeredBearingService layeredBearingService) {
        this.layeredBearingService = layeredBearingService;
    }

    /**
     * 对一份分层剖面做一次核算：先折算出等效指标，再走现有单层计算路径。
     * 响应摊开折算明细（窗口、各层贡献、等效指标）与最终因子、qu。
     */
    @PostMapping("/calculate-layered")
    public LayeredBearingResultResponse calculateLayered(@RequestBody LayeredCalculateRequest request) {
        List<SoilLayer> inlineLayers = request.layers() == null
                ? null
                : WebDtoMapper.toSoilLayers(request.layers());
        double width = WebDtoMapper.require(request.widthM(), "widthM", "WIDTH_MISSING");
        double depth = WebDtoMapper.require(request.depthM(), "depthM", "DEPTH_MISSING");

        boolean named = request.layeredProfileName() != null
                && !request.layeredProfileName().isBlank();
        LayeredBearingResult result = layeredBearingService.calculate(
                request.layeredProfileName(), inlineLayers, width, depth, request.shape());

        return LayeredBearingResultResponse.from(result,
                named ? "LAYERED_PROFILE" : "INLINE_LAYERS",
                named ? request.layeredProfileName().trim() : null);
    }
}
