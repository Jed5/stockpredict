package com.stock.controller;

import com.stock.model.StockRecommend;
import com.stock.service.StockRecommendService;
import com.stock.service.DataCollectionService;
import com.stock.repository.StockDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock
    private StockRecommendService stockRecommendService;

    @Mock
    private DataCollectionService dataCollectionService;

    @Mock
    private StockDailyRepository stockDailyRepository;

    private StockController controller;

    @BeforeEach
    void setUp() {
        controller = new StockController(stockRecommendService, dataCollectionService, stockDailyRepository);
    }

    private StockRecommend createRecommend(Long id, String code, String name,
            String signalType, String source, Double confidence) {
        StockRecommend rec = new StockRecommend();
        rec.setId(id);
        rec.setStockCode(code);
        rec.setStockName(name);
        rec.setRecommendDate(LocalDate.now());
        rec.setSignalType(signalType);
        rec.setSignalSource(source);
        rec.setConfidenceScore(confidence);
        rec.setTargetPrice(150.0);
        rec.setStopLoss(140.0);
        rec.setReason("测试推荐理由");
        rec.setCreatedAt(LocalDateTime.now());
        return rec;
    }

    // ========== /recommend/today 测试 ==========

    @Test
    void getTodayRecommendations_returnsListOfRecommendations() {
        List<StockRecommend> mockList = new ArrayList<>();
        mockList.add(createRecommend(1L, "600519", "贵州茅台", "BUY", "TECHNICAL", 85.0));
        mockList.add(createRecommend(2L, "000858", "五粮液", "SELL", "ML", 72.0));
        when(stockRecommendService.getTodayRecommendations()).thenReturn(mockList);

        List<StockRecommend> result = controller.getTodayRecommendations();

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(stockRecommendService, times(1)).getTodayRecommendations();
    }

    @Test
    void getTodayRecommendations_whenEmpty_returnsEmptyList() {
        when(stockRecommendService.getTodayRecommendations()).thenReturn(new ArrayList<>());

        List<StockRecommend> result = controller.getTodayRecommendations();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getTodayRecommendations_whenServiceThrows_propagatesException() {
        when(stockRecommendService.getTodayRecommendations())
                .thenThrow(new RuntimeException("Database error"));

        assertThrows(Exception.class, () -> controller.getTodayRecommendations());
    }

    // ========== /recommend/buy 测试 ==========

    @Test
    void getBuyRecommendations_returnsOnlyBuySignals() {
        List<StockRecommend> mockList = new ArrayList<>();
        mockList.add(createRecommend(1L, "600519", "贵州茅台", "BUY", "TECHNICAL", 85.0));
        when(stockRecommendService.getBuyRecommendations()).thenReturn(mockList);

        List<StockRecommend> result = controller.getBuyRecommendations();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("BUY", result.get(0).getSignalType());
    }

    // ========== /analyze/{stockCode} 测试 ==========

    @Test
    void analyzeStock_withValidCode_returnsCombinedRecommendations() {
        List<StockRecommend> mockList = new ArrayList<>();
        mockList.add(createRecommend(1L, "600519", "贵州茅台", "BUY", "TECHNICAL", 80.0));
        mockList.add(createRecommend(2L, "600519", "贵州茅台", "BUY", "ML", 75.0));
        when(stockRecommendService.getCombinedRecommendations("600519")).thenReturn(mockList);

        List<StockRecommend> result = controller.analyzeStock("600519");

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    void analyzeStock_withEmptyResult_returnsEmptyList() {
        when(stockRecommendService.getCombinedRecommendations("999999"))
                .thenReturn(new ArrayList<>());

        List<StockRecommend> result = controller.analyzeStock("999999");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void analyzeStock_verifyServiceCalledWithCorrectCode() {
        when(stockRecommendService.getCombinedRecommendations("600519"))
                .thenReturn(new ArrayList<>());

        controller.analyzeStock("600519");

        verify(stockRecommendService, times(1)).getCombinedRecommendations("600519");
    }

    // ========== /fetch/{stockCode} 测试 ==========

    @Test
    void fetchStockData_whenSuccessful_returnsSuccessTrue() {
        when(dataCollectionService.fetchStockData(eq("600519"), any(), any())).thenReturn(true);

        Map<String, Object> response = controller.fetchStockData("600519");

        assertNotNull(response);
        assertTrue((Boolean) response.get("success"));
        assertEquals("600519", response.get("stockCode"));
    }

    @Test
    void fetchStockData_whenFailed_returnsSuccessFalse() {
        when(dataCollectionService.fetchStockData(eq("600519"), any(), any())).thenReturn(false);

        Map<String, Object> response = controller.fetchStockData("600519");

        assertNotNull(response);
        assertFalse((Boolean) response.get("success"));
        assertEquals("600519", response.get("stockCode"));
    }

    @Test
    void fetchStockData_verifyDataCollectionServiceCalled() {
        when(dataCollectionService.fetchStockData(eq("600519"), any(), any())).thenReturn(true);

        controller.fetchStockData("600519");

        verify(dataCollectionService, times(1)).fetchStockData(
                eq("600519"),
                eq(LocalDate.now().minusDays(100)),
                eq(LocalDate.now())
        );
    }

    // ========== CORS 注解验证 ==========

    @Test
    void controller_hasCrossOriginAnnotation() {
        assertTrue(StockController.class.isAnnotationPresent(
                org.springframework.web.bind.annotation.CrossOrigin.class));
    }

    // ========== 空指针安全测试 ==========

    @Test
    void analyzeStock_whenServiceReturnsNull_doesNotCrash() {
        when(stockRecommendService.getCombinedRecommendations("600519")).thenReturn(null);

        List<StockRecommend> result = controller.analyzeStock("600519");
        assertNull(result);
    }

    // ========== 置信度范围验证 ==========

    @Test
    void verifyRecommendation_confidenceScoreRange() {
        List<StockRecommend> mockList = new ArrayList<>();
        mockList.add(createRecommend(1L, "600519", "贵州茅台", "BUY", "TECHNICAL", 85.0));
        mockList.add(createRecommend(2L, "000858", "五粮液", "BUY", "ML", 95.0));
        when(stockRecommendService.getTodayRecommendations()).thenReturn(mockList);

        List<StockRecommend> result = controller.getTodayRecommendations();

        for (StockRecommend rec : result) {
            assertTrue(rec.getConfidenceScore() <= 100,
                    "ConfidenceScore should not exceed 100");
            assertTrue(rec.getConfidenceScore() >= 0,
                    "ConfidenceScore should not be negative");
        }
    }

    // ========== 集成式验证 ==========

    @Test
    void fullWorkflow_fetchThenAnalyze() {
        when(dataCollectionService.fetchStockData(eq("600519"), any(), any())).thenReturn(true);
        List<StockRecommend> mockList = new ArrayList<>();
        mockList.add(createRecommend(1L, "600519", "贵州茅台", "BUY", "TECHNICAL", 80.0));
        when(stockRecommendService.getCombinedRecommendations("600519")).thenReturn(mockList);

        Map<String, Object> fetchResult = controller.fetchStockData("600519");
        assertNotNull(fetchResult);

        List<StockRecommend> analyzeResult = controller.analyzeStock("600519");
        assertNotNull(analyzeResult);
        assertFalse(analyzeResult.isEmpty());
    }
}
