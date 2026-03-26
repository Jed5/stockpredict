package com.stock.controller;

import com.stock.model.StockDaily;
import com.stock.repository.StockDailyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * K线图表数据接口
 */
@Tag(name = "图表数据", description = "获取K线图表所需的历史数据")
@RestController
@RequestMapping("/api/chart")
public class ChartDataController {

    private final StockDailyRepository repository;

    public ChartDataController(StockDailyRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "获取K线数据", description = "获取指定股票最近N个交易日的历史K线数据")
    @GetMapping("/kline/{stockCode}")
    public Map<String, Object> getKlineData(
            @Parameter(description = "股票代码") @PathVariable String stockCode,
            @Parameter(description = "历史天数，默认60") @RequestParam(defaultValue = "60") int days) {

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        List<StockDaily> history = repository
            .findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, startDate, endDate);

        if (history.isEmpty()) {
            return Map.of("code", stockCode, "name", stockCode, "data", List.of());
        }

        List<Map<String, Object>> candles = new ArrayList<>();
        for (StockDaily d : history) {
            candles.add(Map.of(
                "date", d.getTradeDate().toString(),
                "open", d.getOpen().doubleValue(),
                "high", d.getHigh().doubleValue(),
                "low", d.getLow().doubleValue(),
                "close", d.getClose().doubleValue(),
                "volume", d.getVolume().doubleValue()
            ));
        }

        // 计算MA
        double[] closes = history.stream().mapToDouble(s -> s.getClose().doubleValue()).toArray();
        Map<String, Double[]> ma = calcMA(closes, new int[]{5, 10, 20, 60});

        String name = history.get(0).getStockName();
        if (name == null || name.isBlank()) name = stockCode;

        double latestClose = history.get(history.size() - 1).getClose().doubleValue();
        double firstClose = history.get(0).getClose().doubleValue();
        double change = firstClose > 0 ? ((latestClose - firstClose) / firstClose) * 100 : 0;

        return Map.of(
            "code", stockCode,
            "name", name,
            "data", candles,
            "ma", ma,
            "change", change
        );
    }

    private Map<String, Double[]> calcMA(double[] closes, int[] periods) {
        Map<String, Double[]> result = new HashMap<>();
        for (int p : periods) {
            Double[] ma = new Double[closes.length];
            for (int i = 0; i < closes.length; i++) {
                if (i < p - 1) {
                    ma[i] = null;
                } else {
                    double sum = 0;
                    for (int j = i - p + 1; j <= i; j++) sum += closes[j];
                    ma[i] = Math.round(sum / p * 100.0) / 100.0;
                }
            }
            result.put("MA" + p, ma);
        }
        return result;
    }
}
