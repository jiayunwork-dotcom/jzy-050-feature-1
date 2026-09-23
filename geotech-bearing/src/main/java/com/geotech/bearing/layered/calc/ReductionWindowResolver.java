package com.geotech.bearing.layered.calc;

import com.geotech.bearing.layered.domain.LayerContribution;
import com.geotech.bearing.layered.domain.ReductionWindow;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.layered.validation.LayerValidationException;
import com.geotech.bearing.validation.InvalidInputException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 折算窗口确定 —— 分层折算的第一步，独立成类，不碰单层计算内核。
 *
 * <p>给定基础埋深 Df 与基础宽度 B，参与折算的深度范围为
 * <b>[Df, Df + B/2]</b>：窗口起点是埋深处，不是地表。
 * 埋深落在哪一层内部（或恰好落在某层顶面），窗口就从那一层往下切；
 * 比 Df 更浅的部分不计入折算。</p>
 *
 * <p>各层按其在窗口内实际占到的厚度贡献：某层只有一部分落入窗口就只算那一段；
 * 窗口以下的层完全不参与。窗口探到剖面底面以下时不补任何虚拟层，
 * 实际能取到多少算多少。</p>
 */
@Component
public class ReductionWindowResolver {

    private static final double DEPTH_EPS = 1e-9;

    /**
     * 确定窗口并切出每层的厚度贡献。
     *
     * @param layers 已通过结构校验的分层
     * @param depthM 基础埋深 Df（已通过非负、有限校验）
     * @param widthM 基础宽度 B（已通过为正校验）
     * @return 窗口与每层在窗口内的实际厚度贡献（含贡献为 0 的层，便于人工对账）
     */
    public ResolvedWindow resolve(List<SoilLayer> layers, double depthM, double widthM) {
        double windowTop = depthM;
        double windowBottom = depthM + widthM / 2.0;

        SoilLayer last = layers.get(layers.size() - 1);
        // 埋深已经不在剖面覆盖范围内（在最后一层底面之下）：没有任何层可取，
        // 不允许拿空数据继续折算。
        if (depthM >= last.bottomDepthM() - DEPTH_EPS) {
            throw new InvalidInputException("EMBEDMENT_BELOW_PROFILE",
                    "基础埋深 Df=" + depthM + " m 已达到或超过分层剖面底面（深度 "
                            + last.bottomDepthM() + " m），折算窗口内没有任何土层可取。");
        }

        List<LayerContribution> contributions = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            SoilLayer layer = layers.get(i);
            // 与窗口求交：[层顶, 层底] ∩ [Df, Df+B/2]
            double overlapTop = Math.max(layer.topDepthM(), windowTop);
            double overlapBottom = Math.min(layer.bottomDepthM(), windowBottom);
            double thickness = Math.max(0.0, overlapBottom - overlapTop);
            contributions.add(new LayerContribution(i, layer, thickness));
        }

        boolean truncated = windowBottom > last.bottomDepthM() + DEPTH_EPS;
        return new ResolvedWindow(new ReductionWindow(windowTop, windowBottom),
                List.copyOf(contributions), truncated);
    }

    /**
     * 窗口切分结果。
     *
     * @param window      名义窗口 [Df, Df+B/2]
     * @param contributions 每层在窗口内的实际厚度贡献（按登记顺序，含 0 贡献层）
     * @param truncated   窗口下界是否探出了剖面底面；true 表示只用了剖面内实际可取到的层，
     *                    没有无中生有补层
     */
    public record ResolvedWindow(ReductionWindow window,
                                 List<LayerContribution> contributions,
                                 boolean truncated) {

        /** 窗口内实际取到的总厚度（被剖面底面截断时小于名义窗口长度）。 */
        public double actualWindowLengthM() {
            return contributions.stream()
                    .mapToDouble(LayerContribution::contributedThicknessM)
                    .sum();
        }
    }
}
