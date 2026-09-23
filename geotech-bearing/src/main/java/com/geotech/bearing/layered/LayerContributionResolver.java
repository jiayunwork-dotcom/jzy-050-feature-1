package com.geotech.bearing.layered;

import com.geotech.bearing.domain.ContributionWindow;
import com.geotech.bearing.domain.LayerContribution;
import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.validation.InvalidInputException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 折算窗口的确定：给定基础埋深 Df 与宽度 B，窗口为 {@code [Df, Df + B/2]}。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>窗口起点是<b>埋深处</b>而不是地表——埋深落在剖面第几层内部，
 *       窗口就从那一层内部切开算起，比埋深更浅的部分一律不计入折算；</li>
 *   <li>某层只有一部分落进窗口时，只计落在窗口内的那段厚度；</li>
 *   <li>窗口下界探到剖面最深处以下时，只取实际存在的层，不虚构补层；</li>
 *   <li>窗口起点已经深过整个剖面底界（窗口内一层都取不到）时，拒绝折算并给出
 *       结构化错误——折算不能无中生有。</li>
 * </ul>
 */
@Component
public class LayerContributionResolver {

    /**
     * 贡献厚度的浮点噪音门槛：层与窗口的交集小于该值视为不相交，
     * 避免边界相接的层带着 1e-17 量级的残渣厚度混进贡献列表。
     */
    private static final double CONTRIBUTION_EPS = 1e-12;

    /**
     * 切出折算窗口与窗口内各层的贡献厚度。
     *
     * @param layers 已通过结构校验的分层剖面（第一层贴地表、层间连续、层厚为正）
     * @param depthM 基础埋深 Df（窗口上界），单位 m
     * @param widthM 基础宽度 B，单位 m；窗口高度取 B/2
     * @return 窗口上下界与窗口内各层贡献（按深度顺序，仅含实际参与的层）
     */
    public ContributionWindow resolve(List<SoilLayer> layers, double depthM, double widthM) {
        double windowTop = depthM;
        double windowBottom = depthM + widthM / 2.0;

        List<LayerContribution> contributions = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            SoilLayer layer = layers.get(i);
            // 层与窗口的交集：[max(层顶, 窗口顶), min(层底, 窗口底)]
            double overlapTop = Math.max(layer.topDepthM(), windowTop);
            double overlapBottom = Math.min(layer.bottomDepthM(), windowBottom);
            double overlap = overlapBottom - overlapTop;
            if (overlap > CONTRIBUTION_EPS) {
                contributions.add(new LayerContribution(i + 1, layer, overlap));
            }
        }

        if (contributions.isEmpty()) {
            double profileBottom = layers.get(layers.size() - 1).bottomDepthM();
            throw new InvalidInputException("LAYERED_PROFILE_TOO_SHALLOW",
                    "折算窗口 [" + windowTop + ", " + windowBottom
                            + "] m 内没有任何土层：剖面底界仅 " + profileBottom
                            + " m，基础埋深已达或超过剖面最深处，无法折算。");
        }
        return new ContributionWindow(windowTop, windowBottom, contributions);
    }
}
