package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.model.StockDaily;
import com.stock.model.StockRecommend;
import com.stock.repository.StockDailyRepository;
import com.stock.repository.StockRecommendRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MLPredictionService {

    private final StockDailyRepository stockDailyRepository;
    private final StockRecommendRepository stockRecommendRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Flask 推理服务地址 */
    private static final String FLASK_BASE_URL = "http://localhost:5000";
    /** 是否启用 LSTM（false 则强制回退到综合技术分析策略） */
    private static final boolean USE_LSTM = true;

    /** ========== 综合评分阈值 ========== */
    private static final double BUY_THRESHOLD   =  5.0;   // 总分 ≥ +5  → BUY
    private static final double SELL_THRESHOLD  = -5.0;   // 总分 ≤ -5  → SELL
                                                      // 否则        → HOLD

    public MLPredictionService(StockDailyRepository stockDailyRepository,
                                StockRecommendRepository stockRecommendRepository) {
        this.stockDailyRepository = stockDailyRepository;
        this.stockRecommendRepository = stockRecommendRepository;
    }

    /**
     * 预测股票走势
     * 优先调用 Flask LSTM 推理服务；若不可用，自动回退到综合技术分析策略。
     */
    public StockRecommend predict(String stockCode) {
        List<StockDaily> history = stockDailyRepository
            .findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                stockCode, LocalDate.now().minusDays(200), LocalDate.now());

        if (history == null || history.size() < 60) {
            return null;
        }

        try {
            if (USE_LSTM) {
                StockRecommend result = callLSTMService(stockCode, history);
                if (result != null) return result;
            }
        } catch (Exception e) {
            System.err.println("[ML] LSTM服务不可用: " + e.getMessage() + "，回退到综合技术分析策略");
        }

        return analyzeComprehensive(stockCode, history);
    }

    // ========================================================================
    // 综合技术指标分析主方法
    // ========================================================================

    /**
     * 综合技术指标分析
     * <p>
     * 评分维度（每项 -3 ~ +3 分）：
     *   1. 均线系统（MA5/MA10/MA20/MA60 多头排列、空头排列、金叉、死叉）
     *   2. MACD 指标（DIF/DEA 交叉、MACD 柱状图动能）
     *   3. RSI 指标（超买/超卖、趋势变化）
     *   4. 布林带（价格触及上下轨、布林带收口/开口）
     *   5. 成交量信号（放量上涨、缩量下跌、天量预警）
     * <p>
     * 总分 ≥ +5 → BUY | 总分 ≤ -5 → SELL | 否则 → HOLD
     */
    public StockRecommend analyzeComprehensive(String stockCode, List<StockDaily> history) {
        int n = history.size();
        double[] close = new double[n];
        double[] open  = new double[n];
        double[] high = new double[n];
        double[] low  = new double[n];
        double[] vol  = new double[n];

        for (int i = 0; i < n; i++) {
            close[i] = history.get(i).getClose().doubleValue();
            open[i]  = history.get(i).getOpen().doubleValue();
            high[i]  = history.get(i).getHigh().doubleValue();
            low[i]   = history.get(i).getLow().doubleValue();
            vol[i]   = history.get(i).getVolume().doubleValue();
        }

        // ---------- 计算各项技术指标 ----------
        double[] ma5  = calcMA(close, 5);
        double[] ma10 = calcMA(close, 10);
        double[] ma20 = calcMA(close, 20);
        double[] ma60 = calcMA(close, Math.min(60, n));

        double[] ema12 = calcEMA(close, 12);
        double[] ema26 = calcEMA(close, 26);
        double[] dif   = new double[n];
        double[] dea   = new double[n];
        double[] macd  = new double[n]; // MACD 柱状图 = (DIF - DEA) * 2
        calcMACD(ema12, ema26, dif, dea, macd);

        double[] rsi5  = calcRSI(close, 5);
        double[] rsi14 = calcRSI(close, 14);

        double[][] boll = calcBollinger(close, 20, 2);

        double[] volMa5 = calcMA(vol, 5);

        // ---------- 当前值（最新交易日） ----------
        int last = n - 1;
        double curClose = close[last];
        double curOpen  = open[last];
        double curHigh  = high[last];
        double curLow   = low[last];
        double curVol   = vol[last];

        // ---------- 逐项评分 ----------
        double totalScore = 0.0;
        StringBuilder reason = new StringBuilder();

        // ① 均线系统评分
        double maScore = scoreMA(ma5, ma10, ma20, ma60, last);
        totalScore += maScore;
        reason.append("均线(").append(String.format("%.1f", maScore)).append(") ");

        // ② MACD 评分
        double macdScore = scoreMACD(dif, dea, macd, last);
        totalScore += macdScore;
        reason.append("MACD(").append(String.format("%.1f", macdScore)).append(") ");

        // ③ RSI 评分
        double rsiScore = scoreRSI(rsi5, rsi14, last);
        totalScore += rsiScore;
        reason.append("RSI(").append(String.format("%.1f", rsiScore)).append(") ");

        // ④ 布林带评分
        double bollScore = scoreBollinger(close, boll, last);
        totalScore += bollScore;
        reason.append("布林带(").append(String.format("%.1f", bollScore)).append(") ");

        // ⑤ 成交量评分
        double volScore = scoreVolume(vol, volMa5, close, last);
        totalScore += volScore;
        reason.append("成交量(").append(String.format("%.1f", volScore)).append(") ");

        // ---------- 综合决策 ----------
        String trend, signalType;
        double confidence, predicted;

        if (totalScore >= BUY_THRESHOLD) {
            signalType = "BUY";
            trend = "UP";
            // 置信度 = 50 + score * 5，上限 95
            confidence = Math.min(95, 50 + totalScore * 5);
            predicted = curClose * (1 + 0.03 + totalScore * 0.01);
        } else if (totalScore <= SELL_THRESHOLD) {
            signalType = "SELL";
            trend = "DOWN";
            confidence = Math.min(95, 50 + Math.abs(totalScore) * 5);
            predicted = curClose * (1 - 0.03 - Math.abs(totalScore) * 0.01);
        } else {
            signalType = "HOLD";
            trend = "STABLE";
            confidence = 50.0;
            predicted = curClose;
        }

        StockDaily latest = history.get(last);
        StockRecommend rec = new StockRecommend();
        rec.setStockCode(stockCode);
        rec.setStockName(latest.getStockName());
        rec.setRecommendDate(LocalDate.now());
        rec.setSignalType(signalType);
        rec.setSignalSource("ML");
        rec.setConfidenceScore(confidence);
        rec.setTargetPrice(round(predicted, 2));
        rec.setStopLoss(round(curClose * (signalType.equals("BUY") ? 0.95 : 0.97), 2));
        rec.setReason(String.format("综合技术分析[总分%.1f]: %s | %s",
                totalScore, trend, reason.toString().trim()));
        rec.setCreatedAt(LocalDateTime.now());

        return rec;
    }

    // ========================================================================
    // 高级技术指标分析（新增）
    // 包含: SAR / KDJ / OBV / 斐波那契回撤 / 背离检测 / 多级别共振
    // ========================================================================

    /**
     * 高级技术指标综合分析
     * <p>
     * 评分维度（每项 -2 ~ +3 分）：
     *   1. SAR 抛物转向（趋势追踪止损）
     *   2. KDJ 随机指标（超买超卖 + 金叉死叉）
     *   3. OBV 能量潮（量价配合）
     *   4. 斐波那契回撤位（关键支撑压力）
     *   5. 背离检测（顶背离/底背离预警）
     *   6. 多级别趋势共振（短/中/长期）
     * <p>
     * 综合评分 ≥ +5 → BUY | ≤ -5 → SELL | 否则 → HOLD
     * 同时输出详细诊断信息和关键价位。
     *
     * @param stockCode 股票代码
     * @param history 历史行情数据（至少120日）
     * @return 高级分析结果的推荐对象
     */
    public StockRecommend analyzeAdvanced(String stockCode, List<StockDaily> history) {
        int n = history.size();
        if (n < 60) {
            // 数据不足，降级到综合分析
            return analyzeComprehensive(stockCode, history);
        }

        double[] close  = new double[n];
        double[] open   = new double[n];
        double[] high   = new double[n];
        double[] low    = new double[n];
        double[] volume = new double[n];

        for (int i = 0; i < n; i++) {
            close[i]  = history.get(i).getClose().doubleValue();
            open[i]   = history.get(i).getOpen().doubleValue();
            high[i]   = history.get(i).getHigh().doubleValue();
            low[i]    = history.get(i).getLow().doubleValue();
            volume[i] = history.get(i).getVolume().doubleValue();
        }

        int last = n - 1;
        double curClose = close[last];

        // ---- 计算所有高等指标 ----
        double[] ma5   = calcMA(close, 5);
        double[] ma10  = calcMA(close, 10);
        double[] ma20  = calcMA(close, 20);
        double[] ma60  = calcMA(close, Math.min(60, n));

        double[] ema12 = calcEMA(close, 12);
        double[] ema26 = calcEMA(close, 26);
        double[] dif   = new double[n];
        double[] dea   = new double[n];
        double[] macd  = new double[n];
        calcMACD(ema12, ema26, dif, dea, macd);

        double[] rsi6  = calcRSI(close, 6);
        double[] rsi14 = calcRSI(close, 14);
        double[][] boll = calcBollinger(close, 20, 2);

        // --- SAR 抛物转向 ---
        double[] sar = calcSAR(high, low);
        boolean aboveSAR = curClose > sar[last];
        boolean sarUptrend = sar[last] < high[last]; // SAR在价格下方=上涨

        // --- KDJ ---
        double[] k = new double[n];
        double[] d = new double[n];
        double[] j = new double[n];
        calcKDJ(high, low, close, k, d, j);
        double kv = k[last], dv = d[last], jv = j[last];
        boolean kdGoldenCross = kv > dv && k[last - 1] <= d[last - 1];
        boolean kdDeadCross   = kv < dv && k[last - 1] >= d[last - 1];

        // --- OBV ---
        double[] obv = calcOBV(close, volume);
        boolean obvRising = obv[last] > obv[Math.max(0, last - 5)];

        // --- 斐波那契回撤（近120日区间）---
        double fib0    = calcFibonacciLevel(high, low, 120, 0.0);
        double fib236  = calcFibonacciLevel(high, low, 120, 0.236);
        double fib382  = calcFibonacciLevel(high, low, 120, 0.382);
        double fib500  = calcFibonacciLevel(high, low, 120, 0.500);
        double fib618  = calcFibonacciLevel(high, low, 120, 0.618);
        double fib786  = calcFibonacciLevel(high, low, 120, 0.786);
        double fib100  = calcFibonacciLevel(high, low, 120, 1.0);

        // --- 背离检测（近30日） ---
        DivergenceResult[] divergences = detectDivergence(close, dif, rsi6, dates(history), last);

        // --- 逐项评分 ---
        double totalScore = 0.0;
        List<String> signalDetails = new ArrayList<>();

        // ① SAR 评分
        double sarScore = 0;
        if (aboveSAR && sarUptrend) {
            sarScore = 2;  // SAR多头保护
            signalDetails.add("SAR多头保护(+2)");
        } else if (!aboveSAR) {
            sarScore = -2; // SAR反转，趋势转弱
            signalDetails.add("SAR在价格上方(-2)");
        } else {
            sarScore = 0;
            signalDetails.add("SAR中性(0)");
        }
        totalScore += sarScore;

        // ② KDJ 评分
        double kdjScore = 0;
        if (jv < 20) {
            kdjScore = 2;  // 严重超卖，买入机会
            signalDetails.add("KDJ严重超卖(+2)");
        } else if (jv < 35) {
            kdjScore = 1;  // 偏低
            signalDetails.add("KDJ偏低(+1)");
        } else if (jv > 80) {
            kdjScore = -2; // 严重超买
            signalDetails.add("KDJ严重超买(-2)");
        } else if (jv > 65) {
            kdjScore = -1; // 偏高
            signalDetails.add("KDJ偏高(-1)");
        }
        if (kdGoldenCross) {
            kdjScore = Math.min(3, kdjScore + 1);
            signalDetails.add("KDJ金叉(+1)");
        } else if (kdDeadCross) {
            kdjScore = Math.max(-3, kdjScore - 1);
            signalDetails.add("KDJ死叉(-1)");
        }
        totalScore += kdjScore;

        // ③ OBV 评分
        double obvScore = 0;
        boolean priceUp = curClose > close[last - 1];
        double vol20Avg = calcMA(volume, 20)[last];
        boolean volBig  = volume[last] > vol20Avg * 1.3;

        if (obvRising && priceUp) {
            obvScore = 2;  // 量价齐升
            signalDetails.add("OBV量价齐升(+2)");
        } else if (!obvRising && priceUp) {
            obvScore = 0;  // 价涨量缩，警惕
            signalDetails.add("OBV价涨量缩(0)");
        } else if (!obvRising && !priceUp && !volBig) {
            obvScore = 1;  // 价跌量缩，健康调整
            signalDetails.add("OBV价跌量缩(+1)");
        } else if (!obvRising && !priceUp && volBig) {
            obvScore = -2; // 价跌量增，恐慌
            signalDetails.add("OBV恐慌抛售(-2)");
        }
        totalScore += obvScore;

        // ④ 斐波那契评分
        double fibScore = 0;
        if (curClose > fib618) {
            fibScore = 2;  // 在61.8%上方，多头强势
            signalDetails.add("价格在斐波61.8%上方(+2)");
        } else if (curClose > fib500) {
            fibScore = 1;  // 在50%上方，偏多
            signalDetails.add("价格在斐波50%上方(+1)");
        } else if (curClose > fib382) {
            fibScore = 0;  // 中性区域
            signalDetails.add("价格在斐波38.2%~50%中性(0)");
        } else {
            fibScore = -1; // 跌破38.2%，偏空
            signalDetails.add("价格跌破斐波38.2%(-1)");
        }
        totalScore += fibScore;

        // ⑤ 背离评分
        int confirmedBottom = 0, confirmedTop = 0, unconfirmed = 0;
        for (DivergenceResult dr : divergences) {
            if (dr.confirmed) {
                if ("BOTTOM".equals(dr.type)) confirmedBottom++;
                else confirmedTop++;
            } else {
                unconfirmed++;
            }
        }
        double divScore = 0;
        divScore += Math.min(2, confirmedBottom * 2);   // 已确认底背离 ×2
        divScore += Math.min(1, unconfirmed);         // 未确认底背离 ×1
        divScore -= Math.min(2, confirmedTop * 1);   // 已确认顶背离 ×1
        divScore = Math.max(-3, Math.min(3, divScore));
        totalScore += divScore;
        if (divScore > 0) signalDetails.add("底背离信号(+" + (int) divScore + ")");
        else if (divScore < 0) signalDetails.add("顶背离预警(" + (int) divScore + ")");

        // ⑥ 多级别均线共振评分
        double maScore = 0;
        boolean ma5above10  = curClose > ma5[last]  && ma5[last]  > ma10[last];
        boolean ma10above20 = curClose > ma10[last] && ma10[last] > ma20[last];
        boolean ma20above60 = curClose > ma20[last] && ma20[last] > ma60[last];
        int maBullCount = (curClose > ma5[last]  ? 1 : 0)
                        + (curClose > ma10[last] ? 1 : 0)
                        + (curClose > ma20[last] ? 1 : 0)
                        + (curClose > ma60[last] ? 1 : 0);
        if (maBullCount == 4) {
            maScore = 3; signalDetails.add("四周期均线多头(+3)");
        } else if (maBullCount == 3) {
            maScore = 2; signalDetails.add("三周期均线多头(+2)");
        } else if (maBullCount == 2) {
            maScore = 0; signalDetails.add("两周期均线支撑(0)");
        } else {
            maScore = -2; signalDetails.add("均线空头排列(-2)");
        }
        // DIF方向加分
        double difTrend = dif[last] - dif[Math.max(0, last - 3)];
        if (difTrend > 0) {
            maScore = Math.min(4, maScore + 1);
            signalDetails.add("DIF拐头向上(+1)");
        } else {
            maScore = Math.max(-3, maScore - 1);
            signalDetails.add("DIF继续下降(-1)");
        }
        totalScore += maScore;

        // --- 综合决策 ---
        String signalType, trend;
        double confidence, predicted;

        if (totalScore >= 5) {
            signalType = "BUY";
            trend = "UP";
            confidence = Math.min(95, 50 + totalScore * 5);
            predicted = curClose * (1 + 0.03 + totalScore * 0.01);
        } else if (totalScore <= -5) {
            signalType = "SELL";
            trend = "DOWN";
            confidence = Math.min(95, 50 + Math.abs(totalScore) * 5);
            predicted = curClose * (1 - 0.03 - Math.abs(totalScore) * 0.01);
        } else {
            signalType = "HOLD";
            trend = "STABLE";
            confidence = 50.0;
            predicted = curClose;
        }

        // --- 构建reason---
        StringBuilder reason = new StringBuilder();
        reason.append("高级分析[总分").append(String.format("%.0f", totalScore)).append("]:");
        reason.append(trend).append(" | ");
        for (String s : signalDetails) {
            reason.append(s).append(" ");
        }
        // 添加斐波关键位
        reason.append(" | 斐波61.8%:").append(String.format("%.2f", fib618));
        reason.append(" 38.2%:").append(String.format("%.2f", fib382));
        // 添加背离摘要
        reason.append(" | 背离:已确认底").append(confirmedBottom).append("个/顶").append(confirmedTop).append("个");

        StockDaily latest = history.get(last);
        StockRecommend rec = new StockRecommend();
        rec.setStockCode(stockCode);
        rec.setStockName(latest.getStockName());
        rec.setRecommendDate(LocalDate.now());
        rec.setSignalType(signalType);
        rec.setSignalSource("ML_ADVANCED");
        rec.setConfidenceScore(confidence);
        rec.setTargetPrice(round(predicted, 2));
        // 止损：斐波38.2% 或布林下轨
        double stopLoss = fib382 > 0 ? fib382 : boll[last][2];
        rec.setStopLoss(round(stopLoss, 2));
        rec.setReason(reason.toString().trim());
        rec.setCreatedAt(LocalDateTime.now());

        return rec;
    }

    // ========================================================================
    // 高等指标计算工具
    // ========================================================================

    /**
     * 计算 SAR 抛物转向指标
     * @param high 最高价数组
     * @param low  最低价数组
     * @return SAR 数组
     */
    private double[] calcSAR(double[] high, double low[]) {
        int n = high.length;
        double[] sar = new double[n];
        int[] trend = new int[n]; // 1=上涨，-1=下跌
        double af = 0.02;         // 加速因子
        final double afMax = 0.2;
        sar[0] = low[0];
        trend[0] = 1;
        double ep = high[0]; // 极值

        for (int i = 1; i < n; i++) {
            if (trend[i - 1] == 1) {
                // 上涨趋势
                sar[i] = sar[i - 1] + af * (ep - sar[i - 1]);
                if (low[i] < sar[i]) {
                    // 反转下跌
                    trend[i] = -1;
                    sar[i] = ep;
                    ep = low[i];
                    af = 0.02;
                } else {
                    trend[i] = 1;
                    if (high[i] > ep) {
                        ep = high[i];
                        af = Math.min(af + 0.02, afMax);
                    }
                    sar[i] = Math.min(sar[i], low[i]);
                }
            } else {
                // 下跌趋势
                sar[i] = sar[i - 1] - af * (sar[i - 1] - ep);
                if (high[i] > sar[i]) {
                    // 反转上涨
                    trend[i] = 1;
                    sar[i] = ep;
                    ep = high[i];
                    af = 0.02;
                } else {
                    trend[i] = -1;
                    if (low[i] < ep) {
                        ep = low[i];
                        af = Math.min(af + 0.02, afMax);
                    }
                    sar[i] = Math.max(sar[i], high[i]);
                }
            }
        }
        return sar;
    }

    /**
     * 计算 KDJ 随机指标
     * @param high  最高价数组
     * @param low   最低价数组
     * @param close 收盘价数组
     * @param kOut  输出 K 值数组
     * @param dOut  输出 D 值数组
     * @param jOut  输出 J 值数组
     */
    private void calcKDJ(double[] high, double[] low, double[] close,
                          double[] kOut, double[] dOut, double[] jOut) {
        int n = close.length;
        int period = 9;
        kOut[period - 1] = 50.0;
        dOut[period - 1] = 50.0;

        for (int i = period - 1; i < n; i++) {
            double wh = Double.MIN_VALUE;
            double wl = Double.MAX_VALUE;
            for (int j = i - period + 1; j <= i; j++) {
                wh = Math.max(wh, high[j]);
                wl = Math.min(wl, low[j]);
            }
            double rsv = (wh == wl) ? 50.0 : 100.0 * (close[i] - wl) / (wh - wl);
            kOut[i] = (2.0 * kOut[i - 1] + rsv) / 3.0;
            dOut[i] = (2.0 * dOut[i - 1] + kOut[i]) / 3.0;
            jOut[i] = 3.0 * kOut[i] - 2.0 * dOut[i];
        }
    }

    /**
     * 计算 OBV 能量潮
     * @param close  收盘价数组
     * @param volume 成交量数组
     * @return OBV 数组
     */
    private double[] calcOBV(double[] close, double[] volume) {
        int n = close.length;
        double[] obv = new double[n];
        obv[0] = volume[0];
        for (int i = 1; i < n; i++) {
            if (close[i] > close[i - 1]) {
                obv[i] = obv[i - 1] + volume[i];
            } else if (close[i] < close[i - 1]) {
                obv[i] = obv[i - 1] - volume[i];
            } else {
                obv[i] = obv[i - 1];
            }
        }
        return obv;
    }

    /**
     * 计算斐波那契回撤位
     * @param high      最高价数组
     * @param low       最低价数组
     * @param window    统计窗口（天数）
     * @param ratio     斐波比例（0.0 / 0.236 / 0.382 / 0.5 / 0.618 / 0.786 / 1.0）
     * @return 斐波那契价位
     */
    private double calcFibonacciLevel(double[] high, double[] low, int window, double ratio) {
        int start = Math.max(0, high.length - window);
        double maxHigh = Double.MIN_VALUE;
        double minLow  = Double.MAX_VALUE;
        for (int i = start; i < high.length; i++) {
            maxHigh = Math.max(maxHigh, high[i]);
            minLow  = Math.min(minLow,  low[i]);
        }
        return minLow + (maxHigh - minLow) * ratio;
    }

    // ========================================================================
    // 背离检测
    // ========================================================================

    /** 背离检测结果 */
    private static class DivergenceResult {
        final String type;     // "BOTTOM" 底背离 / "TOP" 顶背离
        final int dateIndex;    // 发生日期索引
        final String date;      // 发生日期字符串
        final boolean confirmed; // 是否被后续行情确认

        DivergenceResult(String type, int dateIndex, String date, boolean confirmed) {
            this.type = type;
            this.dateIndex = dateIndex;
            this.date = date;
            this.confirmed = confirmed;
        }
    }

    /**
     * 检测价格与指标的背离
     * <p>
     * 底背离：价格创新低，但指标未创新低 → 买入预警<br>
     * 顶背离：价格创新高，但指标未创新高 → 卖出预警
     *
     * @param close  收盘价数组
     * @param dif    MACD DIF 数组
     * @param rsi    RSI 数组
     * @param dates  日期数组（与 close 同索引）
     * @param last   最新索引
     * @return 背离结果列表
     */
    private DivergenceResult[] detectDivergence(double[] close, double[] dif,
                                                  double[] rsi, String[] dates, int last) {
        int window = 30;
        List<DivergenceResult> results = new ArrayList<>();

        for (int i = window; i <= last; i++) {
            int wStart = i - window + 1;

            // ---- DIF 背离 ----
            // 价格是否在窗口内创新低 / 新高
            double priceMin = close[wStart];
            double priceMax = close[wStart];
            for (int j = wStart + 1; j <= i; j++) {
                priceMin = Math.min(priceMin, close[j]);
                priceMax = Math.max(priceMax, close[j]);
            }
            boolean priceNewLow  = close[i] <= priceMin + 0.001; // 价格接近窗口最低
            boolean priceNewHigh = close[i] >= priceMax - 0.001; // 价格接近窗口最高

            // DIF 在前窗口是否新低 / 新高（排除当前点）
            double difMin = dif[wStart];
            double difMax = dif[wStart];
            for (int j = wStart; j < i; j++) {
                difMin = Math.min(difMin, dif[j]);
                difMax = Math.max(difMax, dif[j]);
            }
            boolean difNotLower  = dif[i] > difMin + 0.0001; // DIF 未创新低
            boolean difNotHigher = dif[i] < difMax - 0.0001; // DIF 未创新高

            if (priceNewLow && difNotLower) {
                boolean confirmed = (i + 3 <= last) && (close[i + 3] > close[i]);
                results.add(new DivergenceResult("BOTTOM", i, dates[i], confirmed));
            }
            if (priceNewHigh && difNotHigher) {
                boolean confirmed = (i + 3 <= last) && (close[i + 3] < close[i]);
                results.add(new DivergenceResult("TOP", i, dates[i], confirmed));
            }

            // ---- RSI 背离 ----
            double rsiMin = rsi[wStart];
            double rsiMax = rsi[wStart];
            for (int j = wStart; j < i; j++) {
                rsiMin = Math.min(rsiMin, rsi[j]);
                rsiMax = Math.max(rsiMax, rsi[j]);
            }
            boolean rsiNotLower  = rsi[i] > rsiMin + 0.1;
            boolean rsiNotHigher = rsi[i] < rsiMax - 0.1;

            if (priceNewLow && rsiNotLower) {
                boolean confirmed = (i + 3 <= last) && (close[i + 3] > close[i]);
                results.add(new DivergenceResult("BOTTOM", i, dates[i], confirmed));
            }
            if (priceNewHigh && rsiNotHigher) {
                boolean confirmed = (i + 3 <= last) && (close[i + 3] < close[i]);
                results.add(new DivergenceResult("TOP", i, dates[i], confirmed));
            }
        }
        return results.toArray(new DivergenceResult[0]);
    }

    // Java 不支持方法重载数组 slice，用工具方法代替
    private double min(double[] arr, int from, int to) {
        double m = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) m = Math.min(m, arr[i]);
        return m;
    }

    private double max(double[] arr, int from, int to) {
        double m = Double.MIN_VALUE;
        for (int i = from; i <= to; i++) m = Math.max(m, arr[i]);
        return m;
    }

    // 把 StockDaily 的 tradeDate 转成字符串数组（供背离检测用）
    private String[] dates(List<StockDaily> history) {
        String[] d = new String[history.size()];
        for (int i = 0; i < history.size(); i++) {
            d[i] = history.get(i).getTradeDate().toString();
        }
        return d;
    }

    // ========================================================================
    // 均线系统评分
    // ========================================================================

    /**
     * 均线系统评分逻辑：
     *   +3  多头排列（MA5 > MA10 > MA20 > MA60）
     *   -3  空头排列（MA5 < MA10 < MA20 < MA60）
     *   +2  黄金交叉（MA5 上穿 MA10，前一交易日 MA5 ≤ MA10）
     *   -2  死亡交叉（MA5 下穿 MA10，前一交易日 MA5 ≥ MA10）
     *   +1  MA5 > MA20 且趋势向上
     *   -1  MA5 < MA20 且趋势向下
     *    0  无明显信号
     */
    private double scoreMA(double[] ma5, double[] ma10, double[] ma20, double[] ma60, int idx) {
        if (idx < 1) return 0;

        double m5  = ma5[idx];
        double m10 = ma10[idx];
        double m20 = ma20[idx];
        double m60 = (ma60.length > idx) ? ma60[idx] : m20;

        double score = 0;

        // 多头排列
        if (m5 > m10 && m10 > m20 && m20 > m60) {
            score = 3;
        }
        // 空头排列
        else if (m5 < m10 && m10 < m20 && m20 < m60) {
            score = -3;
        }
        // 金叉 / 死叉（需前一交易日数据）
        else {
            double m5Prev  = ma5[idx - 1];
            double m10Prev = ma10[idx - 1];

            // 黄金交叉：MA5 从下往上穿越 MA10
            if (m5 > m10 && m5Prev <= m10Prev) {
                score = 2;
            }
            // 死亡交叉：MA5 从上往下穿越 MA10
            else if (m5 < m10 && m5Prev >= m10Prev) {
                score = -2;
            }
            // MA5 与 MA20 关系辅助判断
            else if (m5 > m20) {
                score = 1;
            } else if (m5 < m20) {
                score = -1;
            }
        }
        return score;
    }

    // ========================================================================
    // MACD 评分
    // ========================================================================

    /**
     * MACD 评分逻辑：
     *   +3  DIF > 0 且 DIF > DEA（强势买入区）
     *   +2  DIF > DEA 且 DIF 向上拐头（MACD 柱状图收缩但仍正值）
     *   +1  DIF > 0 但 DIF < DEA（多头收敛）
     *   -1  DIF < 0 但 DIF > DEA（空头收敛）
     *   -2  DIF < DEA 且 DIF < 0（弱势卖出区）
     *   -3  DIF < 0 且 DIF < DEA 加速下行
     *    0  DIF 接近 DEA（0 轴附近）
     *
     * 附加：MACD 柱状图扩张/收缩动能
     *   若柱状图由负转正或扩张：买入动能 +1
     *   若柱状图由正转负或收缩：卖出动能 -1
     */
    private double scoreMACD(double[] dif, double[] dea, double[] macd, int idx) {
        if (idx < 1) return 0;

        double d = dif[idx];
        double e = dea[idx];
        double m = macd[idx];
        double dPrev = dif[idx - 1];
        double mPrev = macd[idx - 1];

        double score = 0;

        if (d > 0 && d > e) {
            score = 3;
        } else if (d > 0 && d <= e) {
            score = 1;
        } else if (d < 0 && d < e) {
            score = -3;
        } else if (d < 0 && d >= e) {
            score = -1;
        } else {
            score = 0; // d ≈ 0
        }

        // 动能附加分：MACD 柱状图由负转正（转强信号）
        if (mPrev < 0 && m > 0) {
            score += 1;
        }
        // 动能附加分：MACD 柱状图由正转负（转弱信号）
        else if (mPrev > 0 && m < 0) {
            score -= 1;
        }
        // 动能附加分：柱状图扩张（加速）
        else if (m > 0 && m > mPrev) {
            score += 0.5;
        } else if (m < 0 && m < mPrev) {
            score -= 0.5;
        }

        // 限制在 [-3, +3]
        return Math.max(-3, Math.min(3, score));
    }

    // ========================================================================
    // RSI 评分
    // ========================================================================

    /**
     * RSI 评分逻辑：
     *   +3  RSI5 < 30（严重超卖，极强买入信号）
     *   +2  RSI14 < 30（超卖区）
     *   +1  RSI5 从低位回升（RSI5 上升且 RSI14 < 50）
     *   -1  RSI5 从高位回落（RSI5 下降且 RSI14 > 50）
     *   -2  RSI14 > 70（超买区）
     *   -3  RSI5 > 85（严重超买）
     *    0  RSI 处于中性区间
     */
    private double scoreRSI(double[] rsi5, double[] rsi14, int idx) {
        if (idx < 1) return 0;

        double r5  = rsi5[idx];
        double r14 = rsi14[idx];
        double r5Prev  = rsi5[idx - 1];
        double r14Prev = rsi14[idx - 1];

        double score = 0;

        // 超卖区域
        if (r5 < 30) {
            score = 3;
        } else if (r14 < 30) {
            score = 2;
        }
        // 超买区域
        else if (r5 > 85) {
            score = -3;
        } else if (r14 > 70) {
            score = -2;
        }
        // 趋势变化
        else if (r5 < r14 && r5Prev < r14Prev && r5 > r5Prev) {
            // RSI 低位金叉且正在回升
            score = 1;
        } else if (r5 > r14 && r5Prev > r14Prev && r5 < r5Prev) {
            // RSI 高位死叉且正在回落
            score = -1;
        } else if (r14 < 50 && r5 > r5Prev) {
            // 偏多：RSI14 在中性偏下且 RSI5 正在回升
            score = 0.5;
        } else if (r14 > 50 && r5 < r5Prev) {
            // 偏空：RSI14 在中性偏上且 RSI5 正在回落
            score = -0.5;
        }

        return score;
    }

    // ========================================================================
    // 布林带评分
    // ========================================================================

    /**
     * 布林带评分逻辑（参数：20日均线 ± 2倍标准差）：
     *   +3  价格触及下轨且 RSI 超卖 → 强烈买入
     *   +2  价格触及下轨（布林带开口）
     *   +1  价格接近下轨（< 1% 空间）
     *   -1  价格接近上轨（< 1% 空间）
     *   -2  价格触及上轨（布林带开口）
     *   -3  价格触及上轨且 RSI 超买 → 强烈卖出
     *    0  价格在布林带中轨附近
     *
     * 附加：布林带收口/开口
     *   布林带收口（带宽收窄）→ 突破前兆，+0.5
     *   布林带开口（带宽扩大）→ 趋势延续，方向跟随
     */
    private double scoreBollinger(double[] close, double[][] boll, int idx) {
        if (idx < 1 || boll[0].length < 3) return 0;

        double price = close[idx];
        double upper = boll[idx][0];  // 上轨
        double mid   = boll[idx][1];  // 中轨
        double lower = boll[idx][2];  // 下轨

        // 计算布林带宽度
        double bandwidth = upper - lower;
        double prevBandwidth = boll[idx - 1][0] - boll[idx - 1][2];
        double bandwidthChange = (bandwidth - prevBandwidth) / prevBandwidth;

        double score = 0;

        // 价格触及/接近下轨
        if (price <= lower) {
            score = 2;
        } else if (price < lower * 1.01) {
            score = 1;
        }
        // 价格触及/接近上轨
        else if (price >= upper) {
            score = -2;
        } else if (price > upper * 0.99) {
            score = -1;
        }
        // 价格在上下轨之间，越靠近中轨越中性
        else {
            double position = (price - lower) / (upper - lower); // 0=下轨，1=上轨
            if (position < 0.3) {
                score = 0.5;
            } else if (position > 0.7) {
                score = -0.5;
            }
        }

        // 布林带收口（突破前兆，附加分）
        if (bandwidthChange < -0.05) {
            score += 0.5;
        }
        // 布林带开口（趋势延续）
        else if (bandwidthChange > 0.1) {
            score += (score > 0 ? 0.5 : (score < 0 ? -0.5 : 0));
        }

        return Math.max(-3, Math.min(3, score));
    }

    // ========================================================================
    // 成交量评分
    // ========================================================================

    /**
     * 成交量评分逻辑：
     *   +2  放量上涨：成交量 > 5日均量 × 1.5 且价格上涨
     *   +2  价涨量增：成交量 > 5日均量 × 1.2 且收盘价 > 开盘价
     *   -2  缩量下跌：成交量 < 5日均量 × 0.7 且价格下跌
     *   -2  价跌量增：成交量 > 5日均量 × 1.5 且收盘价 < 开盘价（恐慌抛售）
     *   +1  温和放量上涨
     *   -1  温和缩量下跌
     *    0  量价平稳
     *
     * 天量预警：成交量创 60 日新高 → 反转风险，附加 -1
     */
    private double scoreVolume(double[] vol, double[] volMa5, double[] close, int idx) {
        if (idx < 1) return 0;

        double curVol = vol[idx];
        double curMa5 = volMa5[idx];
        double curClose = close[idx];
        boolean priceRise = curClose > close[idx - 1];

        // 60 日最大成交量（用于天量检测）
        double maxVol60 = 0;
        for (int i = Math.max(0, idx - 59); i <= idx; i++) {
            maxVol60 = Math.max(maxVol60, vol[i]);
        }

        double score = 0;

        // 放量上涨（强势买入）
        if (curVol > curMa5 * 1.5 && priceRise) {
            score = 2;
        }
        // 缩量下跌（弱势确认卖出）
        else if (curVol < curMa5 * 0.7 && !priceRise) {
            score = -2;
        }
        // 温和放量上涨
        else if (curVol > curMa5 * 1.2 && priceRise) {
            score = 1;
        }
        // 温和缩量下跌
        else if (curVol < curMa5 * 0.85 && !priceRise) {
            score = -1;
        }
        // 放量下跌（警惕）
        else if (curVol > curMa5 * 1.5 && !priceRise) {
            score = -1;
        }

        // 天量预警：成交量创 60 日新高，附加反转风险
        if (curVol >= maxVol60 && idx >= 60 && curVol > curMa5 * 2) {
            score -= 1; // 附加反转风险
        }

        return Math.max(-3, Math.min(3, score));
    }

    // ========================================================================
    // 调用 Flask LSTM 推理服务
    // ========================================================================

    private StockRecommend callLSTMService(String stockCode, List<StockDaily> history) throws Exception {
        RestTemplate restTemplate = new RestTemplate();

        try {
            restTemplate.getForObject(FLASK_BASE_URL + "/load/" + stockCode, String.class);
        } catch (Exception e) {
            throw new RuntimeException("Flask模型未加载: " + e.getMessage());
        }

        double[][] features = extractFeatures(history);

        Map<String, Object> body = new HashMap<>();
        body.put("prices", features);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            FLASK_BASE_URL + "/predict", entity, String.class);

        if (response.getStatusCode().value() != 200) {
            throw new RuntimeException("Flask返回错误: HTTP " + response.getStatusCode().value());
        }

        return parseLSTMResponse(stockCode, history, response.getBody());
    }

    // ========================================================================
    // 提取特征矩阵（LSTM 用）
    // ========================================================================

    /**
     * 特征顺序: [open, high, low, close, volume, ma5, ma10, ma20, rsi]
     */
    private double[][] extractFeatures(List<StockDaily> history) {
        int n = history.size();
        double[] close = history.stream().mapToDouble(s -> s.getClose().doubleValue()).toArray();
        double[] open  = history.stream().mapToDouble(s -> s.getOpen().doubleValue()).toArray();
        double[] high  = history.stream().mapToDouble(s -> s.getHigh().doubleValue()).toArray();
        double[] low   = history.stream().mapToDouble(s -> s.getLow().doubleValue()).toArray();
        double[] volume = history.stream().mapToDouble(s -> s.getVolume().doubleValue()).toArray();

        double[][] features = new double[n][9];

        for (int i = 0; i < n; i++) {
            features[i][0] = open[i];
            features[i][1] = high[i];
            features[i][2] = low[i];
            features[i][3] = close[i];
            features[i][4] = volume[i];

            if (i >= 4) {
                double sum = 0;
                for (int j = i - 4; j <= i; j++) sum += close[j];
                features[i][5] = sum / 5;
            } else {
                features[i][5] = close[i];
            }

            if (i >= 9) {
                double sum = 0;
                for (int j = i - 9; j <= i; j++) sum += close[j];
                features[i][6] = sum / 10;
            } else {
                features[i][6] = close[i];
            }

            if (i >= 19) {
                double sum = 0;
                for (int j = i - 19; j <= i; j++) sum += close[j];
                features[i][7] = sum / 20;
            } else {
                features[i][7] = close[i];
            }

            features[i][8] = calculateRSI(close, 14, i);
        }

        return features;
    }

    // ========================================================================
    // 解析 Flask 响应
    // ========================================================================

    private StockRecommend parseLSTMResponse(String stockCode, List<StockDaily> history, String body) throws Exception {
        if (body == null || body.contains("\"error\"")) return null;

        JsonNode node = objectMapper.readTree(body);
        StockDaily latest = history.get(history.size() - 1);
        double currentPrice = latest.getClose().doubleValue();

        String trend = node.has("trend") ? node.get("trend").asText() : "STABLE";
        String recommendation = node.has("recommendation") ? node.get("recommendation").asText() : "HOLD";
        double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 50.0;
        double predictedPrice = node.has("predicted_price") ? node.get("predicted_price").asDouble() : currentPrice;

        StockRecommend rec = new StockRecommend();
        rec.setStockCode(stockCode);
        rec.setStockName(latest.getStockName());
        rec.setRecommendDate(LocalDate.now());
        rec.setSignalType(recommendation);
        rec.setSignalSource("ML");
        rec.setConfidenceScore(confidence);
        rec.setTargetPrice(predictedPrice);
        rec.setStopLoss(currentPrice * 0.95);
        rec.setReason("LSTM模型预测: " + trend + "趋势");
        rec.setCreatedAt(LocalDateTime.now());

        return rec;
    }

    // ========================================================================
    // Fallback MA 趋势策略（原始简单策略，保留作为最低优先级 fallback）
    // ========================================================================

    // ========================================================================
    // Fallback MA 趋势策略（原始简单策略，保留作为最低优先级 fallback）
    // ========================================================================

    /**
     * Fallback MA 趋势策略（原始简单版）
     * 当综合分析不可用时（如数据不足）调用此方法。
     */
    private StockRecommend fallbackMAPredict(String stockCode, List<StockDaily> history) {
        double[] prices = history.stream().mapToDouble(s -> s.getClose().doubleValue()).toArray();
        int n = prices.length;
        double ma5 = 0, ma20 = 0, current = prices[n - 1];

        for (int i = n - 5; i < n; i++) ma5 += prices[i];
        ma5 /= 5;

        for (int i = n - 20; i < n; i++) ma20 += prices[i];
        ma20 /= 20;

        StockDaily latest = history.get(n - 1);

        String trend, signalType;
        double confidence, predicted;

        if (ma5 > ma20 && current > ma5) {
            trend = "UP";
            signalType = "BUY";
            confidence = Math.min(95, 60 + (ma5 / ma20 - 1) * 1000);
            predicted = current * 1.05;
        } else if (ma5 < ma20 && current < ma5) {
            trend = "DOWN";
            signalType = "SELL";
            confidence = Math.min(95, 60 + (1 - ma5 / ma20) * 1000);
            predicted = current * 0.95;
        } else {
            trend = "STABLE";
            signalType = "HOLD";
            confidence = 50;
            predicted = current;
        }

        StockRecommend rec = new StockRecommend();
        rec.setStockCode(stockCode);
        rec.setStockName(latest.getStockName());
        rec.setRecommendDate(LocalDate.now());
        rec.setSignalType(signalType);
        rec.setSignalSource("ML");
        rec.setConfidenceScore(confidence);
        rec.setTargetPrice(predicted);
        rec.setStopLoss(current * 0.95);
        rec.setReason("MA趋势预测(原始): " + trend + " (MA5=" + String.format("%.2f", ma5) + ", MA20=" + String.format("%.2f", ma20) + ")");
        rec.setCreatedAt(LocalDateTime.now());

        return rec;
    }

    // ========================================================================
    // 基础技术指标计算工具
    // ========================================================================

    /**
     * 计算简单移动平均线 MA
     * @param prices 价格数组
     * @param period 周期
     * @return MA 数组（不足周期处填充 0）
     */
    private double[] calcMA(double[] prices, int period) {
        int n = prices.length;
        double[] ma = new double[n];
        for (int i = 0; i < n; i++) {
            if (i >= period - 1) {
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) sum += prices[j];
                ma[i] = sum / period;
            }
        }
        return ma;
    }

    /**
     * 计算指数移动平均线 EMA
     * @param prices 价格数组
     * @param period 周期
     * @return EMA 数组
     */
    private double[] calcEMA(double[] prices, int period) {
        int n = prices.length;
        double[] ema = new double[n];
        double multiplier = 2.0 / (period + 1);
        // 初始 EMA = SMA(前 period 个)
        if (n >= period) {
            double sum = 0;
            for (int i = 0; i < period; i++) sum += prices[i];
            ema[period - 1] = sum / period;
            for (int i = period; i < n; i++) {
                ema[i] = (prices[i] - ema[i - 1]) * multiplier + ema[i - 1];
            }
        }
        return ema;
    }

    /**
     * 计算 MACD 指标
     * @param ema12  12日 EMA
     * @param ema26  26日 EMA
     * @param difOut 输出 DIF 数组
     * @param deaOut 输出 DEA 数组
     * @param macdOut 输出 MACD 柱状图数组
     */
    private void calcMACD(double[] ema12, double[] ema26, double[] difOut, double[] deaOut, double[] macdOut) {
        int n = ema12.length;
        for (int i = 0; i < n; i++) {
            difOut[i] = ema12[i] - ema26[i];
        }
        // DEA = EMA(DIF, 9)
        double multiplier = 2.0 / 10.0; // (9+1)/2
        // 找到第一个有效 DIF 位置
        int startIdx = -1;
        for (int i = 0; i < n; i++) {
            if (difOut[i] != 0) { startIdx = i; break; }
        }
        if (startIdx >= 0 && startIdx + 8 < n) {
            // 初始 DEA 为前 9 个 DIF 的均值
            double sum = 0;
            for (int i = startIdx; i < startIdx + 9; i++) sum += difOut[i];
            deaOut[startIdx + 8] = sum / 9;
            for (int i = startIdx + 9; i < n; i++) {
                deaOut[i] = (difOut[i] - deaOut[i - 1]) * multiplier + deaOut[i - 1];
            }
        }
        for (int i = 0; i < n; i++) {
            macdOut[i] = (difOut[i] - deaOut[i]) * 2; // 柱状图 = (DIF - DEA) × 2
        }
    }

    /**
     * 计算 RSI
     * @param prices 价格数组
     * @param period RSI 周期（常用 6/12/14）
     * @return RSI 数组（0~100）
     */
    private double[] calcRSI(double[] prices, int period) {
        int n = prices.length;
        double[] rsi = new double[n];
        for (int i = 0; i < n; i++) {
            rsi[i] = calculateRSI(prices, period, i);
        }
        return rsi;
    }

    private double calculateRSI(double[] prices, int period, int index) {
        if (index < period) return 50.0;
        double gains = 0, losses = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double diff = prices[i] - prices[i - 1];
            if (diff > 0) gains += diff;
            else losses -= diff;
        }
        if (losses == 0) return 100.0;
        double rs = gains / losses;
        return 100 - (100 / (1 + rs));
    }

    /**
     * 计算布林带（20日均线 ± 2倍标准差）
     * @return double[][] { upper, mid, lower }
     */
    private double[][] calcBollinger(double[] prices, int period, double k) {
        int n = prices.length;
        double[][] boll = new double[n][3]; // [][0]=上轨, [][1]=中轨, [][2]=下轨

        for (int i = 0; i < n; i++) {
            if (i >= period - 1) {
                // 计算 period 区间均值（标准 SMA）
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) sum += prices[j];
                double sma = sum / period;

                // 计算标准差
                double variance = 0;
                for (int j = i - period + 1; j <= i; j++) {
                    double diff = prices[j] - sma;
                    variance += diff * diff;
                }
                double stdDev = Math.sqrt(variance / period);

                boll[i][0] = sma + k * stdDev; // 上轨
                boll[i][1] = sma;              // 中轨
                boll[i][2] = sma - k * stdDev; // 下轨
            }
        }
        return boll;
    }

    /**
     * 四舍五入到指定小数位
     */
    private double round(double value, int places) {
        if (places < 0) return value;
        return BigDecimal.valueOf(value)
                .setScale(places, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
