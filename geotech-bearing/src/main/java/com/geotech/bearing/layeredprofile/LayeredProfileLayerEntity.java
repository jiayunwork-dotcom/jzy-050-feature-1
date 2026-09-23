package com.geotech.bearing.layeredprofile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 分层剖面中的一层（持久化行）：顶面深度、层厚与本层三指标。
 * 隶属某一份分层剖面，{@code layerIndex}（自 0 起）标记其在剖面中的深度顺序。
 */
@Entity
@Table(name = "layered_profile_layers")
public class LayeredProfileLayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private LayeredProfileEntity profile;

    /** 层序（自 0 起），0 为贴地表的第一层 */
    @Column(name = "layer_index", nullable = false)
    private int layerIndex;

    /** 本层顶面深度（自地表起算），m */
    @Column(name = "top_depth_m", nullable = false)
    private double topDepthM;

    /** 本层层厚，m */
    @Column(name = "thickness_m", nullable = false)
    private double thicknessM;

    /** 本层黏聚力 c，kPa */
    @Column(name = "cohesion_kpa", nullable = false)
    private double cohesionKpa;

    /** 本层内摩擦角 φ，度 */
    @Column(name = "friction_angle_deg", nullable = false)
    private double frictionAngleDeg;

    /** 本层重度 γ，kN/m³ */
    @Column(name = "unit_weight_kn_m3", nullable = false)
    private double unitWeightKnM3;

    protected LayeredProfileLayerEntity() {
        // JPA 要求
    }

    public LayeredProfileLayerEntity(int layerIndex, double topDepthM, double thicknessM,
                                     double cohesionKpa, double frictionAngleDeg,
                                     double unitWeightKnM3) {
        this.layerIndex = layerIndex;
        this.topDepthM = topDepthM;
        this.thicknessM = thicknessM;
        this.cohesionKpa = cohesionKpa;
        this.frictionAngleDeg = frictionAngleDeg;
        this.unitWeightKnM3 = unitWeightKnM3;
    }

    void attachTo(LayeredProfileEntity profile) {
        this.profile = profile;
    }

    public Long getId() {
        return id;
    }

    public int getLayerIndex() {
        return layerIndex;
    }

    public double getTopDepthM() {
        return topDepthM;
    }

    public double getThicknessM() {
        return thicknessM;
    }

    public double getCohesionKpa() {
        return cohesionKpa;
    }

    public double getFrictionAngleDeg() {
        return frictionAngleDeg;
    }

    public double getUnitWeightKnM3() {
        return unitWeightKnM3;
    }
}
