package com.geotech.bearing.layered.domain;

import java.util.List;

/**
 * 分层剖面的装配：登记侧每层只给「层厚 + 三个指标」，
 * 这里自上而下累加层厚，还原成带绝对顶/底面深度的 {@link SoilLayer} 序列。
 *
 * <p>累加天然从深度 0 起算；后续是否留空/重叠由 {@code LayeredProfileValidator}
 * 在绝对深度区间上判定。</p>
 */
public final class LayeredProfileAssembler {

    private LayeredProfileAssembler() {
    }

    /**
     * 装配输入：一层的原始登记数据（层厚 + c、φ、γ）。
     */
    public record LayerInput(double thicknessM,
                             double cohesionKpa,
                             double frictionAngleDeg,
                             double unitWeightKnM3) {
    }

    /**
     * 按登记顺序把各层厚度累加为深度区间。第 i 层顶面 = 前 i 层厚度之和，
     * 第一层顶面固定为 0。
     */
    public static List<SoilLayer> assemble(List<LayerInput> inputs) {
        java.util.List<SoilLayer> layers = new java.util.ArrayList<>(inputs.size());
        double top = 0.0;
        for (LayerInput in : inputs) {
            double bottom = top + in.thicknessM();
            layers.add(new SoilLayer(top, bottom,
                    in.cohesionKpa(), in.frictionAngleDeg(), in.unitWeightKnM3()));
            top = bottom;
        }
        return List.copyOf(layers);
    }
}
