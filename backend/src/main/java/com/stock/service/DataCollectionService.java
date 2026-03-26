package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.model.StockDaily;
import com.stock.repository.StockDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DataCollectionService {

    private static final Logger log = LoggerFactory.getLogger(DataCollectionService.class);

    private final StockDailyRepository stockDailyRepository;
    private final StockInfoService stockInfoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataCollectionService(StockDailyRepository stockDailyRepository,
                                 StockInfoService stockInfoService) {
        this.stockDailyRepository = stockDailyRepository;
        this.stockInfoService = stockInfoService;
    }

    /**
     * 调用Python AKShare脚本获取股票数据
     */
    public boolean fetchStockData(String stockCode, LocalDate startDate, LocalDate endDate) {
        String scriptPath = "/tmp/fetch_stock.py";
        log.info("[DataCollection] fetchStockData 开始: stockCode={} startDate={} endDate={}", stockCode, startDate, endDate);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "python3", scriptPath, stockCode, startDate.toString(), endDate.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            log.info("[DataCollection] Python 进程已启动, pid hash={}", process.hashCode());

            // 用独立线程读取 stdout，避免缓冲 区填满导致进程阻塞
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                        log.debug("[DataCollection] Python stdout: {}", line);
                    }
                } catch (Exception e) {
                    log.error("[DataCollection] 读取 Python stdout 异常: {}", e.getMessage());
                }
            });
            readerThread.start();

            // 最多等待 2 分钟
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            readerThread.join(5000); // 等待读取线程结束

            if (!finished) {
                log.error("[DataCollection] Python 进程超时，已强制终止");
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();
            String result = output.toString();
            log.info("[DataCollection] Python 进程结束, exitCode={}, outputLength={}", exitCode, result.length());

            if (exitCode == 0) {
                List<StockDaily> dataList = parseStockData(result, stockCode);
                log.info("[DataCollection] 解析出 {} 条数据", dataList.size());
                if (!dataList.isEmpty()) {
                    stockDailyRepository.saveAll(dataList);
                    log.info("[DataCollection] 成功保存 {} 条数据到数据库", dataList.size());
                    return true;
                } else {
                    log.warn("[DataCollection] 解析结果为空, raw={}", result.substring(0, Math.min(200, result.length())));
                }
            } else {
                log.error("[DataCollection] Python 进程异常退出, exitCode={}, output={}", exitCode, result);
            }
        } catch (Exception e) {
            log.error("[DataCollection] fetchStockData 异常: {}", e.getMessage(), e);
        }
        return false;
    }

    private List<StockDaily> parseStockData(String jsonData, String stockCode) {
        List<StockDaily> result = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(jsonData);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            for (JsonNode node : array) {
                StockDaily daily = new StockDaily();
                daily.setStockCode(stockCode);
                daily.setStockName(stockInfoService.getStockName(stockCode));
                daily.setTradeDate(LocalDate.parse(node.get("date").asText(), formatter));
                daily.setOpen(BigDecimal.valueOf(node.get("open").asDouble()));
                daily.setHigh(BigDecimal.valueOf(node.get("high").asDouble()));
                daily.setLow(BigDecimal.valueOf(node.get("low").asDouble()));
                daily.setClose(BigDecimal.valueOf(node.get("close").asDouble()));
                daily.setVolume(BigDecimal.valueOf(node.get("volume").asDouble()));
                daily.setAmount(BigDecimal.valueOf(node.get("amount").asDouble()));
                result.add(daily);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
}
