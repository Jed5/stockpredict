package com.stock.controller;

import com.stock.model.StockRecommend;
import com.stock.model.StockDaily;
import com.stock.repository.StockDailyRepository;
import com.stock.service.MLPredictionService;
import com.stock.service.StockRecommendService;
import com.stock.service.DataCollectionService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock")
@CrossOrigin(origins = "*")
public class StockController {

    private final StockRecommendService stockRecommendService;
    private final DataCollectionService dataCollectionService;
    private final StockDailyRepository stockDailyRepository;
    private final MLPredictionService mlPredictionService;

    public StockController(StockRecommendService stockRecommendService,
                         DataCollectionService dataCollectionService,
                         StockDailyRepository stockDailyRepository,
                         MLPredictionService mlPredictionService) {
        this.stockRecommendService = stockRecommendService;
        this.dataCollectionService = dataCollectionService;
        this.stockDailyRepository = stockDailyRepository;
        this.mlPredictionService = mlPredictionService;
    }

    /**
     * 获取今日推荐股票
     */
    @GetMapping("/recommend/today")
    public List<StockRecommend> getTodayRecommendations() {
        return stockRecommendService.getTodayRecommendations();
    }

    /**
     * 获取买入推荐
     */
    @GetMapping("/recommend/buy")
    public List<StockRecommend> getBuyRecommendations() {
        return stockRecommendService.getBuyRecommendations();
    }

    /**
     * 分析指定股票
     */
    @PostMapping("/analyze/{stockCode}")
    public List<StockRecommend> analyzeStock(@PathVariable String stockCode) {
        return stockRecommendService.getCombinedRecommendations(stockCode);
    }

    /**
     * 高级技术分析（SAR/KDJ/OBV/斐波那契/背离）
     */
    @PostMapping("/analyze/advanced/{stockCode}")
    public StockRecommend analyzeAdvanced(@PathVariable String stockCode) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(250);
        List<StockDaily> history = stockDailyRepository
            .findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, startDate, endDate);
        if (history == null || history.size() < 60) {
            return null;
        }
        return mlPredictionService.analyzeAdvanced(stockCode, history);
    }

    /**
     * 获取股票数据
     */
    @PostMapping("/fetch/{stockCode}")
    public Map<String, Object> fetchStockData(@PathVariable String stockCode) {
        boolean success = dataCollectionService.fetchStockData(
            stockCode, 
            java.time.LocalDate.now().minusDays(100),
            java.time.LocalDate.now()
        );
        return Map.of("success", success, "stockCode", stockCode);
    }

    /**
     * 调试接口：直接插入股票数据（用于测试）
     * Body: [{"stockCode":"300059","stockName":"东方财富","tradeDate":"2026-01-05","open":23.22,"high":23.75,"low":23.21,"close":23.75,"volume":356523390,"amount":8400324243}]
     */
    @PostMapping("/debug/seed")
    public Map<String, Object> debugSeed(@RequestBody List<Map<String, Object>> records) {
        try {
            List<StockDaily> list = records.stream().map(m -> {
                StockDaily sd = new StockDaily();
                sd.setStockCode((String) m.get("stockCode"));
                sd.setStockName((String) m.get("stockName"));
                sd.setTradeDate(LocalDate.parse((String) m.get("tradeDate")));
                sd.setOpen(BigDecimal.valueOf(((Number) m.get("open")).doubleValue()));
                sd.setHigh(BigDecimal.valueOf(((Number) m.get("high")).doubleValue()));
                sd.setLow(BigDecimal.valueOf(((Number) m.get("low")).doubleValue()));
                sd.setClose(BigDecimal.valueOf(((Number) m.get("close")).doubleValue()));
                sd.setVolume(BigDecimal.valueOf(((Number) m.get("volume")).doubleValue()));
                sd.setAmount(BigDecimal.valueOf(((Number) m.get("amount")).doubleValue()));
                return sd;
            }).toList();
            stockDailyRepository.saveAll(list);
            return Map.of("success", true, "count", list.size());
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}
