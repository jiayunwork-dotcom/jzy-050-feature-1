package com.geotech.bearing.layered.profile;

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
 * 分层剖面中的一层（子表）。
 *
 * <p>每层保存登记顺序 {@code layerOrder}（从 0 起）、<b>层厚</b>（登记原值）与该层三个指标；
 * 顶面/底面绝对深度在取出后由装配器重算，保证「分层信息 = 各层层厚」是唯一事实来源，
 * 不会出现顶层深度与层厚存成两份不一致的数据。</p>
 */
@Entity
@Table(name = "soil_layers")
public class SoilLayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private LayeredProfileEntity profile;

    /** 层在剖面中的序号，从 0 起（避免使用 SQL 保留字 index 作列名）。 */
    @Column(name = "layer_order", nullable = false)
    private int layerOrder;

    /** 层厚，m，必须为正（登记前已做结构校验）。 */
    @Column(name = "thickness_m", nullable = false)
    private double thicknessM;

    @Column(name = "cohesion_kpa", nullable = false)
    private double cohesionKpa;

    @Column(name = "friction_angle_deg", nullable = false)
    private double frictionAngleDeg;

    @Column(name = "unit_weight_kn_m3", nullable = false)
    private double unitWeightKnM3;

    protected SoilLayerEntity() {
        // JPA 要求
    }

    SoilLayerEntity(LayeredProfileEntity profile, int layerOrder, double thicknessM,
                    double cohesionKpa, double frictionAngleDeg, double unitWeightKnM3) {
        this.profile = profile;
        this.layerOrder = layerOrder;
        this.thicknessM = thicknessM;
        this.cohesionKpa = cohesionKpa;
        this.frictionAngleDeg = frictionAngleDeg;
        this.unitWeightKnM3 = unitWeightKnM3;
    }

    public Long getId() {
        return id;
    }

    public int getLayerOrder() {
        return layerOrder;
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
