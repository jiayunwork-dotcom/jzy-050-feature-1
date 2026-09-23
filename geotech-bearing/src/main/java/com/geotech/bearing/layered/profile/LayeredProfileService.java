package com.geotech.bearing.layered.profile;

import com.geotech.bearing.layered.domain.LayeredProfile;
import com.geotech.bearing.layered.domain.LayeredProfileAssembler;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.layered.validation.LayeredProfileValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 分层剖面的登记、点名取出、列出 —— 独立于单层参数档的
 * {@code SoilProfileService}，操作 {@code layered_soil_profiles / soil_layers} 两张表。
 *
 * <p>登记时即便不经 Web 层也同样先过 {@link LayeredProfileValidator} 结构校验，
 * 杜绝非法分层入库；{@code name} 唯一约束兜底并发同名，只有一笔登记成功。</p>
 */
@Service
public class LayeredProfileService {

    private final LayeredProfileRepository repository;
    private final LayeredProfileValidator validator;

    public LayeredProfileService(LayeredProfileRepository repository,
                                 LayeredProfileValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * 匹配唯一约束冲突（PostgreSQL/H2/通用文案，识别口径与单层档服务一致）。
     */
    private static final Pattern UNIQUE_VIOLATION = Pattern.compile(
            "(?i)unique[_ ]constraint|unique[_ ]index|unique_violation|duplicate key");

    @Transactional
    public LayeredProfile register(String name,
                                   String description,
                                   List<LayeredProfileAssembler.LayerInput> rawLayers) {
        if (repository.existsByName(name)) {
            throw new LayeredProfileAlreadyExistsException(name);
        }
        // 层厚累加为绝对深度并做全套结构校验，非法分层不允许入库。
        List<SoilLayer> layers = LayeredProfileAssembler.assemble(rawLayers);
        validator.validate(layers);

        LayeredProfileEntity entity = new LayeredProfileEntity(name, description);
        for (int i = 0; i < rawLayers.size(); i++) {
            LayeredProfileAssembler.LayerInput in = rawLayers.get(i);
            entity.addLayer(i, in.thicknessM(), in.cohesionKpa(),
                    in.frictionAngleDeg(), in.unitWeightKnM3());
        }
        try {
            LayeredProfileEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (RuntimeException ex) {
            if (isUniqueNameViolation(ex)) {
                throw new LayeredProfileAlreadyExistsException(name);
            }
            throw ex;
        }
    }

    private boolean isUniqueNameViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DataIntegrityViolationException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && UNIQUE_VIOLATION.matcher(msg).find()) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<LayeredProfile> list() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(LayeredProfileService::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public LayeredProfile get(String name) {
        return toDomain(repository.findByName(name)
                .orElseThrow(() -> new LayeredProfileNotFoundException(name)));
    }

    /** 点名取出（核算编排调用）；未登记抛 {@link LayeredProfileNotFoundException}。 */
    @Transactional(readOnly = true)
    public LayeredProfile requireProfile(String name) {
        return get(name);
    }

    /**
     * 在事务/会话内把实体（含懒加载子层）映射为脱离会话的领域视图：
     * 各层按层厚重算顶/底面深度，登记顺序原样保留。
     */
    private static LayeredProfile toDomain(LayeredProfileEntity entity) {
        List<LayeredProfileAssembler.LayerInput> raw = new ArrayList<>();
        for (SoilLayerEntity e : entity.getLayers()) {
            raw.add(new LayeredProfileAssembler.LayerInput(
                    e.getThicknessM(), e.getCohesionKpa(),
                    e.getFrictionAngleDeg(), e.getUnitWeightKnM3()));
        }
        List<SoilLayer> layers = LayeredProfileAssembler.assemble(raw);
        return new LayeredProfile(entity.getName(), entity.getDescription(),
                entity.getCreatedAt(), layers);
    }
}
