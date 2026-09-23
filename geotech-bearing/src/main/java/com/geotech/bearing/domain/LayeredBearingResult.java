package com.geotech.bearing.domain;

/**
 * 一次分层剖面核算的完整结果：折算过程明细 + 用等效指标算出的承载力结果。
 *
 * <p>{@code bearingResult} 由现有单层计算内核产出（因子、形状系数、三项分项、qu），
 * 其 {@code soilParameters} 即折算出的等效指标；{@code reduction} 把折算这一步
 * 摊开来，供人工核对窗口与各层贡献。</p>
 *
 * @param reduction     折算明细（窗口上下界、各层贡献厚度、等效指标）
 * @param bearingResult 等效指标经现有太沙基内核算出的承载力结果
 */
public record LayeredBearingResult(LayeredReduction reduction,
                                   BearingResult bearingResult) {

    public double qu() {
        return bearingResult.qu();
    }
}
