package com.geotech.bearing.layered.calc;

import com.geotech.bearing.domain.BearingResult;
import com.geotech.bearing.layered.domain.LayerContribution;
import com.geotech.bearing.layered.domain.ReductionWindow;
import com.geotech.bearing.domain.SoilParameters;

import java.util.List;

/**
 * 一次分层剖面核算的完整结果：不只是黑箱 qu，还把折算过程摊开。
 *
 * @param window                 折算窗口 [Df, Df+B/2]
 * @param truncated              窗口下界是否探出剖面底面（实际可取厚度小于名义窗口）
 * @param contributions          每层在窗口内的实际厚度贡献（按登记顺序）
 * @param actualWindowLengthM    窗口内实际取到的总厚度，m
 * @param equivalentSoil         折算得到的等效单层指标（c_eq、φ_eq、γ_eq）
 * @param bearingResult          等效指标走既有单层计算路径得到的因子、形状系数、分项与 qu
 */
public record LayeredBearingResult(ReductionWindow window,
                                   boolean truncated,
                                   List<LayerContribution> contributions,
                                   double actualWindowLengthM,
                                   SoilParameters equivalentSoil,
                                   BearingResult bearingResult) {

    public double quKpa() {
        return bearingResult.qu();
    }
}
