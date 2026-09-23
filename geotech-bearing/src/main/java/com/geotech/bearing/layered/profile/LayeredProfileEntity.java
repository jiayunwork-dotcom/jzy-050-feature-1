package com.geotech.bearing.layered.profile;

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
 * 具名「分层剖面」实体（剖面头）—— 与单层参数档表 {@code soil_profiles} 完全独立，
 * 两边互不覆盖、互不干扰。{@code name} 唯一，作为点名取用的键，
 * 与单层档的同名档也互不影响（不同表、不同唯一约束）。
 */
@Entity
@Table(name = "layered_soil_profiles")
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

    /** 各层按登记顺序（{@code layerOrder} 升序）持有，随剖面头级联增删。 */
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("layerOrder ASC")
    private List<SoilLayerEntity> layers = new ArrayList<>();

    protected LayeredProfileEntity() {
        // JPA 要求
    }

    public LayeredProfileEntity(String name, String description) {
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public void addLayer(int layerOrder, double thicknessM, double cohesionKpa,
                         double frictionAngleDeg, double unitWeightKnM3) {
        layers.add(new SoilLayerEntity(this, layerOrder, thicknessM, cohesionKpa,
                frictionAngleDeg, unitWeightKnM3));
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

    public List<SoilLayerEntity> getLayers() {
        return layers;
    }
}
