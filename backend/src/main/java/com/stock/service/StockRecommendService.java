package com.stock.service;

import com.stock.model.StockRecommend;
import com.stock.repository.StockRecommendRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StockRecommendService {

    private final TechnicalAnalysisService technicalAnalysisService;
    private final MLPredictionService mlPredictionService;
    private final StockRecommendRepository stockRecommendRepository;
    private final StockInfoService stockInfoService;

    public StockRecommendService(TechnicalAnalysisService technicalAnalysisService,
                                MLPredictionService mlPredictionService,
                                StockRecommendRepository stockRecommendRepository,
                                StockInfoService stockInfoService) {
        this.technicalAnalysisService = technicalAnalysisService;
        this.mlPredictionService = mlPredictionService;
        this.stockRecommendRepository = stockRecommendRepository;
        this.stockInfoService = stockInfoService;
    }

    /**
     * 综合两种方式选股，返回推荐列表
     */
    public List<StockRecommend> getCombinedRecommendations(String stockCode) {
        List<StockRecommend> results = new ArrayList<>();

        // 技术分析
        List<StockRecommend> techResults = technicalAnalysisService.analyze(stockCode);
        results.addAll(techResults);

        // ML预测
        StockRecommend mlResult = mlPredictionService.predict(stockCode);
        if (mlResult != null) {
            results.add(mlResult);
        }

        // 填充股票名称
        for (StockRecommend rec : results) {
            if (rec.getStockName() == null || rec.getStockName().isEmpty()) {
                rec.setStockName(stockInfoService.getStockName(rec.getStockCode()));
            }
        }

        // 如果两种方式都推荐，取置信度高的；来源为BOTH表示综合两者
        return mergeRecommendations(results);
    }

    /**
     * 获取今日所有推荐
     */
    public List<StockRecommend> getTodayRecommendations() {
        List<StockRecommend> recs = stockRecommendRepository.findByRecommendDateOrderByConfidenceScoreDesc(LocalDate.now());
        // 填充缺失的股票名称
        for (StockRecommend rec : recs) {
            if (rec.getStockName() == null || rec.getStockName().isEmpty()) {
                rec.setStockName(stockInfoService.getStockName(rec.getStockCode()));
            }
        }
        return recs;
    }

    /**
     * 获取买入推荐
     */
    public List<StockRecommend> getBuyRecommendations() {
        List<StockRecommend> recs = stockRecommendRepository.findBySignalTypeAndRecommendDate("BUY", LocalDate.now());
        for (StockRecommend rec : recs) {
            if (rec.getStockName() == null || rec.getStockName().isEmpty()) {
                rec.setStockName(stockInfoService.getStockName(rec.getStockCode()));
            }
        }
        return recs;
    }

    /**
     * 合并重复推荐的股票
     */
    private List<StockRecommend> mergeRecommendations(List<StockRecommend> all) {
        return all.stream()
            .collect(Collectors.groupingBy(StockRecommend::getStockCode))
            .values()
            .stream()
            .map(group -> {
                if (group.size() == 1) {
                    return group.get(0);
                }
                // 多个信号时，取置信度最高的，并将来源标记为BOTH
                StockRecommend best = group.stream()
                    .max(Comparator.comparingDouble(StockRecommend::getConfidenceScore))
                    .orElse(group.get(0));
                best.setSignalSource("BOTH");
                return best;
            })
            .sorted(Comparator.comparingDouble(StockRecommend::getConfidenceScore).reversed())
            .collect(Collectors.toList());
    }
}
