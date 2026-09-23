package com.geotech.bearing.layeredprofile;

import com.geotech.bearing.domain.SoilLayer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 分层剖面的登记、查询、列出与点名取用。与单层参数档服务互不依赖、分表存储。
 *
 * <p>所有写操作在单个数据库事务内完成；{@code name} 上有唯一约束兜底，
 * 即使并发登记同名也只有一笔成功，其余转成 {@link LayeredProfileAlreadyExistsException}。
 * 结构合法性（贴地表 / 连续 / 正层厚）由控制器在调用本服务之前用
 * {@code LayeredProfileValidator} 把关，本服务只负责可靠落库与取出。</p>
 */
@Service
public class LayeredProfileService {

    private final LayeredProfileRepository repository;

    public LayeredProfileService(LayeredProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * 匹配唯一约束冲突（PostgreSQL：unique_violation；H2：Unique index ... violation；
     * 以及通用 duplicate key 文案）。与单层参数档服务同款判据。
     */
    private static final Pattern UNIQUE_VIOLATION = Pattern.compile(
            "(?i)unique[_ ]constraint|unique[_ ]index|unique_violation|duplicate key");

    /**
     * 登记一份分层剖面。{@code layers} 必须已按深度顺序排好（第一层贴地表），
     * 层序原样落库，取出时按同一顺序还原。
     */
    @Transactional
    public LayeredProfileEntity register(String name, String description, List<SoilLayer> layers) {
        if (repository.existsByName(name)) {
            throw new LayeredProfileAlreadyExistsException(name);
        }
        LayeredProfileEntity entity = new LayeredProfileEntity(name, description);
        for (int i = 0; i < layers.size(); i++) {
            SoilLayer layer = layers.get(i);
            entity.addLayer(new LayeredProfileLayerEntity(
                    i, layer.topDepthM(), layer.thicknessM(),
                    layer.cohesionKpa(), layer.frictionAngleDeg(), layer.unitWeightKnM3()));
        }
        try {
            return repository.saveAndFlush(entity);
        } catch (RuntimeException ex) {
            // 并发登记同名时由 name 唯一约束兜底；不同驱动包装层级不一，按根因文案识别。
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
    public List<LayeredProfileEntity> list() {
        return repository.findAllWithLayersOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public LayeredProfileEntity get(String name) {
        return repository.findByNameWithLayers(name)
                .orElseThrow(() -> new LayeredProfileNotFoundException(name));
    }

    /** 点名取用并还原为按深度顺序排列的领域层列表，供折算使用。 */
    @Transactional(readOnly = true)
    public List<SoilLayer> requireLayers(String name) {
        return get(name).getLayers().stream()
                .map(e -> new SoilLayer(e.getTopDepthM(), e.getThicknessM(),
                        e.getCohesionKpa(), e.getFrictionAngleDeg(), e.getUnitWeightKnM3()))
                .toList();
    }
}
