package com.stock.service;

import com.stock.model.StockDaily;
import com.stock.model.StockRecommend;
import com.stock.repository.StockDailyRepository;
import com.stock.repository.StockRecommendRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TechnicalAnalysisService {

    private final StockDailyRepository stockDailyRepository;
    private final StockRecommendRepository stockRecommendRepository;

    public TechnicalAnalysisService(StockDailyRepository stockDailyRepository,
                                   StockRecommendRepository stockRecommendRepository) {
        this.stockDailyRepository = stockDailyRepository;
        this.stockRecommendRepository = stockRecommendRepository;
    }

    /**
     * 技术分析选股
     */
    public List<StockRecommend> analyze(String stockCode) {
        List<StockRecommend> results = new ArrayList<>();
        LocalDate today = LocalDate.now();

        List<StockDaily> history = stockDailyRepository
            .findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                stockCode, today.minusDays(100), today);

        if (history == null || history.size() < 30) return results;

        // 计算技术指标
        Map<String, Object> indicators = calculateIndicators(history);

        // 金叉买入信号（若历史不足11天则不存在，默认false）
        boolean goldenCross = (boolean) indicators.getOrDefault("goldenCross", false);
        // 死叉卖出信号
        boolean deathCross = (boolean) indicators.getOrDefault("deathCross", false);
        // MACD买入
        boolean macdBuy = (boolean) indicators.getOrDefault("macdBuy", false);
        // RSI超卖
        boolean rsiOversold = (boolean) indicators.getOrDefault("rsiOversold", false);
        // 放量上涨
        boolean volumeUp = (boolean) indicators.getOrDefault("volumeUp", false);

        StockDaily latest = history.get(history.size() - 1);
        double confidence = calculateConfidence(indicators);

        if (goldenCross && macdBuy && confidence > 60) {
            StockRecommend rec = createRecommend(latest, "BUY", "TECHNICAL", confidence, 
                "技术指标金叉+MACD买入信号", indicators);
            results.add(rec);
        } else if (deathCross && confidence > 70) {
            StockRecommend rec = createRecommend(latest, "SELL", "TECHNICAL", confidence,
                "技术指标死叉+MACD卖出信号", indicators);
            results.add(rec);
        }

        return results;
    }

    private Map<String, Object> calculateIndicators(List<StockDaily> history) {
        Map<String, Object> result = new HashMap<>();
        int n = history.size();

        // 计算收盘价数组
        double[] closePrices = history.stream()
            .mapToDouble(s -> s.getClose().doubleValue())
            .toArray();

        // MA5, MA10, MA20
        double ma5 = calculateMA(closePrices, Math.min(5, n));
        double ma10 = calculateMA(closePrices, Math.min(10, n));
        double ma20 = calculateMA(closePrices, Math.min(20, n));
        double ma60 = calculateMA(closePrices, Math.min(60, n));

        // 金叉：MA5上穿MA10，死叉：MA5下穿MA10
        // 需要比较"前一个交易日"的均线值和"当前交易日"的均线值
        if (n >= 11) {
            // 计算前一日（index = n-2）的 MA5 和 MA10
            // 使用 calculateMAForPeriod 确保用历史窗口计算，而非单日价格
            double ma5PrevReal = calculateMAForPeriod(closePrices, 5, n - 2);
            double ma10PrevReal = calculateMAForPeriod(closePrices, 10, n - 2);
            // 计算今日（index = n-1）的 MA5 和 MA10
            double ma5Now = calculateMA(closePrices, 5);
            double ma10Now = calculateMA(closePrices, 10);

            // 金叉：昨日 MA5 <= MA10，今日 MA5 > MA10
            boolean goldenCross = ma5PrevReal <= ma10PrevReal && ma5Now > ma10Now;
            // 死叉：昨日 MA5 >= MA10，今日 MA5 < MA10
            boolean deathCross = ma5PrevReal >= ma10PrevReal && ma5Now < ma10Now;

            result.put("goldenCross", goldenCross);
            result.put("deathCross", deathCross);
        }

        // MACD
        double[] macd = calculateMACD(closePrices);
        result.put("macdBuy", macd[0] > macd[1] && macd[1] < 0);
        result.put("macdValue", macd[0]);

        // RSI
        double rsi = calculateRSI(closePrices, 14);
        result.put("rsi", rsi);
        result.put("rsiOversold", rsi < 30);
        result.put("rsiOverbought", rsi > 70);

        // 成交量分析
        if (n >= 6) {
            double avgVolume5 = calculateAvgVolume(history, 5);
            double volumeToday = history.get(n - 1).getVolume().doubleValue();
            result.put("volumeUp", volumeToday > avgVolume5 * 1.5);
        }

        result.put("ma5", ma5);
        result.put("ma10", ma10);
        result.put("ma20", ma20);
        result.put("ma60", ma60);
        result.put("currentPrice", closePrices[n - 1]);

        return result;
    }

    private double calculateMA(double[] prices, int period) {
        if (period > prices.length) period = prices.length;
        double sum = 0;
        for (int i = prices.length - period; i < prices.length; i++) {
            sum += prices[i];
        }
        return sum / period;
    }

    /**
     * 计算指定范围内（前 end 天，不包含 index=end 那天）的移动平均
     * 用于计算历史某个时间点的均线值
     */
    private double calculateMAForPeriod(double[] prices, int period, int endIndex) {
        // 取 endIndex 之前 period 天的数据
        // 即 indices: endIndex-period, ..., endIndex-1
        int startIndex = Math.max(0, endIndex - period + 1);
        int actualPeriod = endIndex - startIndex + 1;
        if (actualPeriod <= 0) return 0;
        double sum = 0;
        for (int i = startIndex; i <= endIndex && i < prices.length; i++) {
            sum += prices[i];
        }
        return sum / actualPeriod;
    }

    private double[] calculateMACD(double[] prices) {
        // MACD(12,26,9)
        // Step 1: 计算 EMA12 和 EMA26（对价格）
        double ema12 = calculateEMA(prices, 12);
        double ema26 = calculateEMA(prices, 26);
        double dif = ema12 - ema26;

        // Step 2: 计算 DIF 的 EMA（MACD 平滑信号线）
        // 先构建 DIF 序列（每一天的 DIF = EMA12 - EMA26）
        double[] difSeries = new double[prices.length];
        for (int i = 0; i < prices.length; i++) {
            double e12 = calculateEMAForPeriod(prices, 12, i);
            double e26 = calculateEMAForPeriod(prices, 26, i);
            difSeries[i] = e12 - e26;
        }
        // 对 DIF 序列计算 EMA(9)
        double dea = calculateEMA(difSeries, 9);

        double bar = (dif - dea) * 2;
        return new double[]{dif, dea, bar};
    }

    /**
     * 计算 EMA（指数移动平均）
     * EMA_t = α × price_t + (1-α) × EMA_{t-1}
     * α = 2 / (period + 1)
     */
    private double calculateEMA(double[] prices, int period) {
        if (prices == null || prices.length == 0) return 0;
        if (prices.length < period) period = prices.length;
        double ema = prices[0];
        double multiplier = 2.0 / (period + 1);
        for (int i = 1; i < prices.length; i++) {
            ema = (prices[i] - ema) * multiplier + ema;
        }
        return ema;
    }

    /**
     * 计算截止到指定索引的 EMA（用于 MACD 中 DIF 的平滑）
     */
    private double calculateEMAForPeriod(double[] prices, int period, int endIndex) {
        if (endIndex < 0) return 0;
        int actualEnd = Math.min(endIndex, prices.length - 1);
        if (actualEnd < period - 1) {
            // 数据不足 period 天，简单平均
            double sum = 0;
            for (int i = 0; i <= actualEnd; i++) sum += prices[i];
            return sum / (actualEnd + 1);
        }
        // 先用 SMA 初始化前 period 天的 EMA
        double ema = 0;
        for (int i = 0; i < period; i++) {
            ema += prices[actualEnd - period + 1 + i];
        }
        ema /= period;
        // 再做 EMA 递推
        double multiplier = 2.0 / (period + 1);
        for (int i = actualEnd - period + 1 + period; i <= actualEnd; i++) {
            if (i < prices.length) {
                ema = (prices[i] - ema) * multiplier + ema;
            }
        }
        return ema;
    }

    private double calculateRSI(double[] prices, int period) {
        if (prices.length < period + 1) return 50;
        
        double gains = 0, losses = 0;
        for (int i = prices.length - period; i < prices.length; i++) {
            double diff = prices[i] - prices[i - 1];
            if (diff > 0) gains += diff;
            else losses -= diff;
        }
        
        if (losses == 0) return 100;
        double rs = gains / losses;
        return 100 - (100 / (1 + rs));
    }

    private double calculateAvgVolume(List<StockDaily> history, int days) {
        int start = Math.max(0, history.size() - days - 1);
        double sum = 0;
        for (int i = start; i < history.size() - 1; i++) {
            sum += history.get(i).getVolume().doubleValue();
        }
        return sum / (history.size() - start - 1);
    }

    private double calculateConfidence(Map<String, Object> indicators) {
        double confidence = 50; // 基础分
        
        if ((boolean) indicators.getOrDefault("goldenCross", false)) confidence += 20;
        if ((boolean) indicators.getOrDefault("macdBuy", false)) confidence += 20;
        if ((boolean) indicators.getOrDefault("rsiOversold", false)) confidence += 15;
        if ((boolean) indicators.getOrDefault("volumeUp", false)) confidence += 15;
        if ((boolean) indicators.getOrDefault("deathCross", false)) confidence += 25;
        
        return Math.min(100, confidence);
    }

    private StockRecommend createRecommend(StockDaily stock, String signalType, 
        String source, double confidence, String reason, Map<String, Object> indicators) {
        StockRecommend rec = new StockRecommend();
        rec.setStockCode(stock.getStockCode());
        rec.setStockName(stock.getStockName());
        rec.setRecommendDate(LocalDate.now());
        rec.setSignalType(signalType);
        rec.setSignalSource(source);
        rec.setConfidenceScore(confidence);
        rec.setTargetPrice((double) indicators.get("currentPrice") * (signalType.equals("BUY") ? 1.1 : 0.9));
        rec.setStopLoss((double) indicators.get("currentPrice") * 0.95);
        rec.setReason(reason);
        rec.setCreatedAt(LocalDateTime.now());
        return rec;
    }
}
