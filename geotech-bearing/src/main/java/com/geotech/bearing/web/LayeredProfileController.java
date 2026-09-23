package com.geotech.bearing.web;

import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.layered.LayeredProfileValidator;
import com.geotech.bearing.layeredprofile.LayeredProfileEntity;
import com.geotech.bearing.layeredprofile.LayeredProfileService;
import com.geotech.bearing.web.dto.LayeredProfileResponse;
import com.geotech.bearing.web.dto.RegisterLayeredProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分层剖面的登记、查询、列出接口。与单层参数档接口平行、互不干扰；
 * 分层剖面持久化，重启后仍可点名取用。
 */
@RestController
@RequestMapping("/api/layered-profiles")
public class LayeredProfileController {

    private final LayeredProfileService layeredProfileService;
    private final LayeredProfileValidator layeredValidator;

    public LayeredProfileController(LayeredProfileService layeredProfileService,
                                    LayeredProfileValidator layeredValidator) {
        this.layeredProfileService = layeredProfileService;
        this.layeredValidator = layeredValidator;
    }

    /** 登记一份分层剖面；结构非法 400（指出第几层），同名 409。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LayeredProfileResponse register(@Valid @RequestBody RegisterLayeredProfileRequest request) {
        List<SoilLayer> layers = WebDtoMapper.toSoilLayers(request.layers());
        layeredValidator.validate(layers); // 落库前先过结构 + 每层物理校验，杜绝非法剖面入库
        LayeredProfileEntity saved = layeredProfileService.register(
                request.name().trim(), request.description(), layers);
        return toResponse(saved);
    }

    /** 列出全部已登记分层剖面（按名称排序，各含原始分层列表）。 */
    @GetMapping
    public List<LayeredProfileResponse> list() {
        return layeredProfileService.list().stream()
                .map(LayeredProfileController::toResponse)
                .toList();
    }

    /** 按名取出一份分层剖面的原始分层列表；未登记返回 404 结构化错误。 */
    @GetMapping("/{name}")
    public LayeredProfileResponse get(@PathVariable String name) {
        return toResponse(layeredProfileService.get(name));
    }

    private static LayeredProfileResponse toResponse(LayeredProfileEntity e) {
        List<LayeredProfileResponse.LayerView> layers = e.getLayers().stream()
                .map(l -> new LayeredProfileResponse.LayerView(
                        l.getTopDepthM(), l.getThicknessM(),
                        l.getCohesionKpa(), l.getFrictionAngleDeg(), l.getUnitWeightKnM3()))
                .toList();
        return new LayeredProfileResponse(
                e.getName(), e.getDescription(), e.getCreatedAt(), layers);
    }
}
