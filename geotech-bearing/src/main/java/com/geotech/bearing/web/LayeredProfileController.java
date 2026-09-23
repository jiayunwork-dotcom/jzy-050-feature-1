package com.geotech.bearing.web;

import com.geotech.bearing.layered.domain.LayeredProfile;
import com.geotech.bearing.layered.domain.LayeredProfileAssembler;
import com.geotech.bearing.layered.profile.LayeredProfileService;
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
 * 分层剖面的登记、按名取出（原始分层列表）、列出接口。
 *
 * <p>与单层参数档接口（{@code /api/profiles}）并行独立：不同表、不同路径，
 * 两边同名互不覆盖。登记前同样先做结构与指标校验，非法分层不落库。</p>
 */
@RestController
@RequestMapping("/api/layered-profiles")
public class LayeredProfileController {

    private final LayeredProfileService layeredProfileService;

    public LayeredProfileController(LayeredProfileService layeredProfileService) {
        this.layeredProfileService = layeredProfileService;
    }

    /** 登记一份分层剖面；同名（分层剖面表内）返回 409，结构/指标非法返回 400。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LayeredProfileResponse register(
            @Valid @RequestBody RegisterLayeredProfileRequest request) {
        List<LayeredProfileAssembler.LayerInput> rawLayers =
                WebDtoMapper.toLayerInputs(request.layers());
        LayeredProfile saved = layeredProfileService.register(
                request.name().trim(), request.description(), rawLayers);
        return toResponse(saved);
    }

    /** 列出全部已登记分层剖面（按名称排序）。 */
    @GetMapping
    public List<LayeredProfileResponse> list() {
        return layeredProfileService.list().stream()
                .map(LayeredProfileController::toResponse)
                .toList();
    }

    /** 按名取出一份分层剖面的原始分层列表；未登记返回 404。 */
    @GetMapping("/{name}")
    public LayeredProfileResponse get(@PathVariable String name) {
        return toResponse(layeredProfileService.get(name));
    }

    private static LayeredProfileResponse toResponse(LayeredProfile p) {
        List<LayeredProfileResponse.LayerView> views = new java.util.ArrayList<>();
        for (int i = 0; i < p.layers().size(); i++) {
            var layer = p.layers().get(i);
            views.add(new LayeredProfileResponse.LayerView(
                    i,
                    layer.thicknessM(),
                    layer.cohesionKpa(),
                    layer.frictionAngleDeg(),
                    layer.unitWeightKnM3()));
        }
        return new LayeredProfileResponse(p.name(), p.description(), p.createdAt(), views);
    }
}
