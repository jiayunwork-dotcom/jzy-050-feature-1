package com.geotech.bearing.layered.calc;

import com.geotech.bearing.layered.domain.LayerContribution;
import com.geotech.bearing.layered.domain.SoilLayer;
import com.geotech.bearing.validation.InvalidInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折算窗口确定测试。
 *
 * <p>窗口为 [Df, Df+B/2]，起点随埋深下移；埋深处所在层从内部切开，
 * 更浅部分不计；部分落入只计落入段；探出剖面底面不补层。</p>
 */
class ReductionWindowResolverTest {

    private final ReductionWindowResolver resolver = new ReductionWindowResolver();

    private static final double EPS = 1e-9;

    /** 0-2 填土 / 2-5 原状土 / 5-10 持力层。 */
    private static final List<SoilLayer> PROFILE = List.of(
            new SoilLayer(0, 2, 10, 10, 18),
            new SoilLayer(2, 5, 30, 30, 20),
            new SoilLayer(5, 10, 5, 25, 19));

    private double contribution(ReductionWindowResolver.ResolvedWindow w, int index) {
        return w.contributions().get(index).contributedThicknessM();
    }

    @Test
    @DisplayName("Df=0、B=4：窗口 [0,2]，第 1 层单独填满整窗口（后面层贡献为 0）")
    void windowAtSurfaceFilledByFirstLayer() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 0.0, 4.0);
        assertEquals(0.0, w.window().topDepthM(), EPS);
        assertEquals(2.0, w.window().bottomDepthM(), EPS);
        assertEquals(2.0, contribution(w, 0), EPS);
        assertEquals(0.0, contribution(w, 1), EPS);
        assertEquals(0.0, contribution(w, 2), EPS);
        assertFalse(w.truncated());
        assertEquals(2.0, w.actualWindowLengthM(), EPS);
    }

    @Test
    @DisplayName("埋深下移：Df=1、B=4 -> 窗口 [1,3]，第 1 层切开 1m、第 2 层 1m，起点不是地表")
    void windowStartsAtEmbedmentDepthAndCutsContainingLayer() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 1.0, 4.0);
        assertEquals(1.0, w.window().topDepthM(), EPS);
        assertEquals(3.0, w.window().bottomDepthM(), EPS);
        assertEquals(1.0, contribution(w, 0), EPS, "埋深以上的 0-1 段不计入");
        assertEquals(1.0, contribution(w, 1), EPS, "第 2 层只计 2-3 这 1m");
        assertEquals(0.0, contribution(w, 2), EPS);
        assertFalse(w.truncated());
        assertEquals(2.0, w.actualWindowLengthM(), EPS);
    }

    @Test
    @DisplayName("埋深继续下移：Df=3、B=4 -> 窗口 [3,5]，完全落在第 2 层内")
    void windowFollowsEmbedmentDownIntoSecondLayer() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 3.0, 4.0);
        assertEquals(3.0, w.window().topDepthM(), EPS);
        assertEquals(5.0, w.window().bottomDepthM(), EPS);
        assertEquals(0.0, contribution(w, 0), EPS);
        assertEquals(2.0, contribution(w, 1), EPS);
        assertEquals(0.0, contribution(w, 2), EPS);
    }

    @Test
    @DisplayName("跨三层的窗口：Df=1、B=12 -> [1,7]，贡献 1+3+2，总厚 6")
    void windowSpansThreeLayersWithPartialTail() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 1.0, 12.0);
        assertEquals(1.0, w.window().topDepthM(), EPS);
        assertEquals(7.0, w.window().bottomDepthM(), EPS);
        assertEquals(1.0, contribution(w, 0), EPS);
        assertEquals(3.0, contribution(w, 1), EPS);
        assertEquals(2.0, contribution(w, 2), EPS, "第 3 层只有 5-7 段进入窗口");
        assertFalse(w.truncated());
        assertEquals(6.0, w.actualWindowLengthM(), EPS);
    }

    @Test
    @DisplayName("窗口探出剖面底面：Df=6、B=12 -> [6,12]，只取 6-10 共 4m，标记 truncated 且不补层")
    void windowBeyondProfileBottomIsTruncatedNoVirtualLayer() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 6.0, 12.0);
        assertEquals(12.0, w.window().bottomDepthM(), EPS, "名义窗口下界仍是 Df+B/2");
        assertTrue(w.truncated());
        assertEquals(0.0, contribution(w, 0), EPS);
        assertEquals(0.0, contribution(w, 1), EPS);
        assertEquals(4.0, contribution(w, 2), EPS, "实际只取剖面底面以上的 4m");
        assertEquals(4.0, w.actualWindowLengthM(), EPS, "实际厚度小于名义 6m");
    }

    @Test
    @DisplayName("埋深正好落在层界面上：Df=2、B=2 -> [2,3]，从第 2 层顶起算，第 1 层贡献 0")
    void embedmentExactlyOnInterfaceStartsLowerLayer() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 2.0, 2.0);
        assertEquals(0.0, contribution(w, 0), EPS);
        assertEquals(1.0, contribution(w, 1), EPS);
    }

    @Test
    @DisplayName("埋深达到/超过剖面底面 -> EMBEDMENT_BELOW_PROFILE，拒绝继续折算")
    void embedmentBelowProfileBottomRejected() {
        InvalidInputException atBottom = assertThrows(InvalidInputException.class,
                () -> resolver.resolve(PROFILE, 10.0, 4.0));
        assertEquals("EMBEDMENT_BELOW_PROFILE", atBottom.getReason());

        InvalidInputException below = assertThrows(InvalidInputException.class,
                () -> resolver.resolve(PROFILE, 12.0, 4.0));
        assertEquals("EMBEDMENT_BELOW_PROFILE", below.getReason());
    }

    @Test
    @DisplayName("贡献为 0 的层仍出现在列表里但不被标记为 participating")
    void zeroContributionLayersListedButNotParticipating() {
        ReductionWindowResolver.ResolvedWindow w = resolver.resolve(PROFILE, 0.0, 4.0);
        List<LayerContribution> all = w.contributions();
        assertEquals(3, all.size());
        assertTrue(all.get(0).participating());
        assertFalse(all.get(1).participating());
        assertFalse(all.get(2).participating());
    }
}
