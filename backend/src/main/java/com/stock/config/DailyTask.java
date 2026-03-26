package com.stock.config;

import com.stock.service.DataCollectionService;
import com.stock.service.StockRecommendService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class DailyTask {

    private final DataCollectionService dataCollectionService;
    private final StockRecommendService stockRecommendService;

    // 默认监控的股票列表
    private static final String[] DEFAULT_STOCKS = {
        "600519", // 贵州茅台
        "000858", // 五粮液
        "601318", // 中国平安
        "600036", // 招商银行
        "000333"  // 美的集团
    };

    public DailyTask(DataCollectionService dataCollectionService,
                    StockRecommendService stockRecommendService) {
        this.dataCollectionService = dataCollectionService;
        this.stockRecommendService = stockRecommendService;
    }

    /**
     * 每天早上9:15执行数据更新和选股
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 15 9 * * *")
    public void dailyStockUpdate() {
        System.out.println("[DailyTask] 开始执行每日股票分析: " + LocalDateTime.now());
        
        for (String stockCode : DEFAULT_STOCKS) {
            try {
                // 1. 获取最新数据
                dataCollectionService.fetchStockData(
                    stockCode,
                    LocalDate.now().minusDays(100),
                    LocalDate.now()
                );
                
                // 2. 分析并推荐
                stockRecommendService.getCombinedRecommendations(stockCode);
                
                System.out.println("[DailyTask] 完成分析: " + stockCode);
            } catch (Exception e) {
                System.err.println("[DailyTask] 分析失败: " + stockCode + " - " + e.getMessage());
            }
        }
        
        System.out.println("[DailyTask] 每日任务完成: " + LocalDateTime.now());
    }
}
