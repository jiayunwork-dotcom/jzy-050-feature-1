package com.geotech.bearing.layered;

import com.geotech.bearing.domain.ContributionWindow;
import com.geotech.bearing.domain.LayerContribution;
import com.geotech.bearing.domain.SoilLayer;
import com.geotech.bearing.validation.InvalidInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 折算窗口确定测试：窗口 [Df, Df+B/2] 的起点随埋深下移，
 * 各层只按落在窗口内的实际厚度参与，剖面不够深时不虚构补层。
 *
 * <p>共用剖面（填土盖原状土再盖深层土）：</p>
 * <pre>
 *   第 1 层 [0, 2)   第 2 层 [2, 5)   第 3 层 [5, 8)
 * </pre>
 */
class LayerContributionResolverTest {

    private static final double EPS = 1e-9;

    private final LayerContributionResolver resolver = new LayerContributionResolver();

    /** 三层剖面：[0,2)、[2,5)、[5,8)，底界 8m。 */
    private static List<SoilLayer> threeLayerProfile() {
        return List.of(
                new SoilLayer(0.0, 2.0, 10, 10, 17),
                new SoilLayer(2.0, 3.0, 30, 30, 19),
                new SoilLayer(5.0, 3.0, 50, 35, 20));
    }

    @Test
    @DisplayName("窗口定义：上界=埋深，下界=埋深+半个宽度")
    void windowBoundsFollowDepthAndWidth() {
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 1.0, 4.0);
        assertEquals(1.0, window.topM(), EPS, "窗口上界必须等于埋深 Df");
        assertEquals(3.0, window.bottomM(), EPS, "窗口下界必须等于 Df + B/2");
    }

    @Test
    @DisplayName("窗口完全落在第一层内：只有第一层参与，厚度恰为 B/2")
    void windowFullyInsideFirstLayer() {
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 0.5, 2.0);
        assertEquals(1, window.contributions().size());
        LayerContribution only = window.contributions().get(0);
        assertEquals(1, only.layerIndex());
        assertEquals(1.0, only.contributingThicknessM(), EPS);
    }

    @Test
    @DisplayName("窗口跨两层：交界处各按窗口内实际厚度参与")
    void windowSpanningTwoLayersSplitsAtBoundary() {
        // 窗口 [1, 3]：第 1 层占 [1,2)=1m，第 2 层占 [2,3]=1m
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 1.0, 4.0);
        assertEquals(2, window.contributions().size());
        assertEquals(1, window.contributions().get(0).layerIndex());
        assertEquals(1.0, window.contributions().get(0).contributingThicknessM(), EPS);
        assertEquals(2, window.contributions().get(1).layerIndex());
        assertEquals(1.0, window.contributions().get(1).contributingThicknessM(), EPS);
    }

    @Test
    @DisplayName("埋深下移到第二层内部：窗口起点跟着下移，第一层完全不参与")
    void windowStartFollowsDepthIntoDeeperLayer() {
        // Df=2.5 落在第 2 层 [2,5) 内部，窗口 [2.5, 3.5] 全在第 2 层内
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 2.5, 2.0);
        assertEquals(2.5, window.topM(), EPS);
        assertEquals(1, window.contributions().size(),
                "比埋深更浅的第一层一律不计入折算");
        assertEquals(2, window.contributions().get(0).layerIndex());
        assertEquals(1.0, window.contributions().get(0).contributingThicknessM(), EPS);
    }

    @Test
    @DisplayName("埋深恰好等于层界：上一层贡献为零，从下一层起算")
    void depthExactlyAtLayerBoundaryStartsFromNextLayer() {
        // Df=2.0 恰为第 1/2 层分界，窗口 [2, 4] 全在第 2 层内
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 2.0, 4.0);
        assertEquals(1, window.contributions().size());
        assertEquals(2, window.contributions().get(0).layerIndex());
        assertEquals(2.0, window.contributions().get(0).contributingThicknessM(), EPS);
    }

    @Test
    @DisplayName("窗口下界探到剖面底界以下：只取实际存在的层，不虚构补层")
    void windowDeeperThanProfileUsesOnlyAvailableLayers() {
        // Df=6, B=8 -> 窗口 [6, 10]，剖面底界只有 8：只有第 3 层的 [6,8)=2m 参与
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 6.0, 8.0);
        assertEquals(10.0, window.bottomM(), EPS, "窗口下界仍按 Df+B/2 定义");
        assertEquals(1, window.contributions().size());
        assertEquals(3, window.contributions().get(0).layerIndex());
        assertEquals(2.0, window.contributions().get(0).contributingThicknessM(), EPS,
                "超出剖面底界的部分不得虚构补层");
    }

    @Test
    @DisplayName("窗口贯穿整个剖面：三层全部参与且总厚度小于窗口高度")
    void windowCoveringWholeProfileTakesAllLayers() {
        // Df=0, B=20 -> 窗口 [0, 10]，剖面只有 8m：三层合计 8m
        ContributionWindow window = resolver.resolve(threeLayerProfile(), 0.0, 20.0);
        assertEquals(3, window.contributions().size());
        double total = window.contributions().stream()
                .mapToDouble(LayerContribution::contributingThicknessM).sum();
        assertEquals(8.0, total, EPS);
    }

    @Test
    @DisplayName("埋深达到或超过剖面底界：窗口内无层可取 -> LAYERED_PROFILE_TOO_SHALLOW")
    void depthBeyondProfileBottomRejected() {
        InvalidInputException atBottom = assertThrows(InvalidInputException.class,
                () -> resolver.resolve(threeLayerProfile(), 8.0, 2.0));
        assertEquals("LAYERED_PROFILE_TOO_SHALLOW", atBottom.getReason());

        InvalidInputException below = assertThrows(InvalidInputException.class,
                () -> resolver.resolve(threeLayerProfile(), 10.0, 2.0));
        assertEquals("LAYERED_PROFILE_TOO_SHALLOW", below.getReason());
    }
}
