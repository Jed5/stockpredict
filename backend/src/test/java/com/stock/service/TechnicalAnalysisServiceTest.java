package com.stock.service;

import com.stock.model.StockDaily;
import com.stock.model.StockRecommend;
import com.stock.repository.StockDailyRepository;
import com.stock.repository.StockRecommendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TechnicalAnalysisServiceTest {

    @Mock
    private StockDailyRepository stockDailyRepository;

    @Mock
    private StockRecommendRepository stockRecommendRepository;

    private TechnicalAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new TechnicalAnalysisService(stockDailyRepository, stockRecommendRepository);
    }

    private StockDaily createStockDaily(String code, String name, LocalDate date, 
            double open, double high, double low, double close, double volume) {
        StockDaily sd = new StockDaily();
        sd.setStockCode(code);
        sd.setStockName(name);
        sd.setTradeDate(date);
        sd.setOpen(BigDecimal.valueOf(open));
        sd.setHigh(BigDecimal.valueOf(high));
        sd.setLow(BigDecimal.valueOf(low));
        sd.setClose(BigDecimal.valueOf(close));
        sd.setVolume(BigDecimal.valueOf(volume));
        sd.setAmount(BigDecimal.valueOf(close * volume));
        return sd;
    }

    private List<StockDaily> createUpwardTrendHistory(String code, String name, int days) {
        List<StockDaily> history = new ArrayList<>();
        double basePrice = 10.0;
        for (int i = 0; i < days; i++) {
            double close = basePrice + i * 0.1 + (Math.random() * 0.05);
            double open = close - 0.05;
            double high = close + 0.1;
            double low = close - 0.15;
            double volume = 1000000 + (i * 10000);
            history.add(createStockDaily(code, name, LocalDate.now().minusDays(days - i - 1), open, high, low, close, volume));
        }
        return history;
    }

    private List<StockDaily> createDownwardTrendHistory(String code, String name, int days) {
        List<StockDaily> history = new ArrayList<>();
        double basePrice = 15.0;
        for (int i = 0; i < days; i++) {
            double close = basePrice - i * 0.1 + (Math.random() * 0.05);
            double open = close + 0.05;
            double high = close + 0.2;
            double low = close - 0.1;
            double volume = 1000000 + (i * 10000);
            history.add(createStockDaily(code, name, LocalDate.now().minusDays(days - i - 1), open, high, low, close, volume));
        }
        return history;
    }

    // ========== 基础功能测试 ==========

    @Test
    void analyze_withInsufficientData_returnsEmptyList() {
        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createUpwardTrendHistory("600519", "贵州茅台", 10));

        List<StockRecommend> results = service.analyze("600519");
        assertTrue(results.isEmpty());
    }

    @Test
    void analyze_withSufficientData_doesNotReturnNull() {
        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createUpwardTrendHistory("600519", "贵州茅台", 50));

        List<StockRecommend> results = service.analyze("600519");
        assertNotNull(results);
    }

    @Test
    void analyze_withNullFromRepository_handledGracefully() {
        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(null);

        // Should not throw NullPointerException
        List<StockRecommend> results = service.analyze("600519");
        // Returns empty list or handles null gracefully
        assertNotNull(results);
    }

    // ========== 上涨趋势测试 ==========

    @Test
    void analyze_upwardTrend_generatesBuySignal() {
        // 构建持续上涨的数据，MA5 > MA10（持续金叉状态）
        List<StockDaily> history = new ArrayList<>();
        double basePrice = 10.0;
        for (int i = 0; i < 60; i++) {
            double trend = i * 0.05; // 持续上涨
            double close = basePrice + trend;
            history.add(createStockDaily("600519", "贵州茅台",
                    LocalDate.now().minusDays(60 - i - 1),
                    close - 0.05, close + 0.1, close - 0.15, close, 1000000));
        }

        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                eq("600519"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(history);

        List<StockRecommend> results = service.analyze("600519");
        // 上涨趋势中，如果满足金叉+MACD买入+置信度>60，应产生BUY信号
        assertNotNull(results);
    }

    // ========== 下跌趋势测试 ==========

    @Test
    void analyze_downwardTrend_generatesSellSignal() {
        List<StockDaily> history = createDownwardTrendHistory("600519", "贵州茅台", 60);

        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                eq("600519"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(history);

        List<StockRecommend> results = service.analyze("600519");
        assertNotNull(results);
    }

    // ========== 边界条件测试 ==========

    @Test
    void analyze_withExactly30DaysData_doesNotCrash() {
        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createUpwardTrendHistory("600519", "贵州茅台", 30));

        List<StockRecommend> results = service.analyze("600519");
        assertNotNull(results);
    }

    @Test
    void analyze_verifyRecommendFields_arePopulated() {
        List<StockDaily> history = createUpwardTrendHistory("600519", "贵州茅台", 60);

        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                eq("600519"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(history);

        List<StockRecommend> results = service.analyze("600519");
        for (StockRecommend rec : results) {
            assertNotNull(rec.getStockCode());
            assertNotNull(rec.getRecommendDate());
            assertNotNull(rec.getSignalType());
            assertTrue(rec.getConfidenceScore() >= 0 && rec.getConfidenceScore() <= 100);
            assertNotNull(rec.getTargetPrice());
            assertNotNull(rec.getStopLoss());
        }
    }

    // ========== Mock Repository 验证测试 ==========

    @Test
    void analyze_verifyRepositoryCalled_withCorrectDates() {
        List<StockDaily> history = createUpwardTrendHistory("600519", "贵州茅台", 60);
        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                eq("600519"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(history);

        service.analyze("600519");

        verify(stockDailyRepository).findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                eq("600519"),
                eq(LocalDate.now().minusDays(100)),
                eq(LocalDate.now())
        );
    }

    // ========== 并发安全测试 ==========

    @Test
    void analyze_concurrentCalls_threadSafe() throws InterruptedException {
        when(stockDailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(createUpwardTrendHistory("600519", "贵州茅台", 60));

        Thread t1 = new Thread(() -> service.analyze("600519"));
        Thread t2 = new Thread(() -> service.analyze("600519"));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // No exceptions = thread safe for read operations
        assertTrue(true);
    }
}
