package com.geotech.bearing.layeredprofile;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 具名「分层剖面」实体：按深度顺序排列的多层土，与单层参数档各自独立存取。
 *
 * <p>每份剖面持久化在 PostgreSQL 中，进程重启后仍可点名取用；{@code name} 唯一，
 * 作为点名取用的键。各层经 {@link LayeredProfileLayerEntity} 级联落库，
 * 按 {@code layerIndex} 保持登记时的深度顺序。</p>
 */
@Entity
@Table(name = "layered_profiles")
public class LayeredProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 各层按 layerIndex 升序保持深度顺序；随剖面级联保存/删除。 */
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("layerIndex ASC")
    private List<LayeredProfileLayerEntity> layers = new ArrayList<>();

    protected LayeredProfileEntity() {
        // JPA 要求
    }

    public LayeredProfileEntity(String name, String description) {
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
    }

    /** 追加一层；层序由调用方按深度顺序赋好。 */
    public void addLayer(LayeredProfileLayerEntity layer) {
        layer.attachTo(this);
        this.layers.add(layer);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LayeredProfileLayerEntity> getLayers() {
        return layers;
    }
}
