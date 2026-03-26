package com.stock.controller;

import com.stock.model.StockDaily;
import com.stock.repository.StockDailyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class BulkImportController {

    private static final Logger log = LoggerFactory.getLogger(BulkImportController.class);
    private final StockDailyRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BulkImportController(StockDailyRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/bulk-import")
    public Map<String, Object> bulkImport(@RequestParam(defaultValue = "/tmp/stock_data_a_2y") String dirPath) {
        Map<String, Object> result = new HashMap<>();
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            result.put("error", "目录不存在: " + dirPath);
            return result;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json")
                && !name.equals("done.json") && !name.equals("failed.json") && !name.equals("meta.json"));

        if (files == null || files.length == 0) {
            result.put("error", "没有找到JSON文件");
            return result;
        }

        int totalStocks = 0;
        int totalRecords = 0;
        int errors = 0;
        List<String> errorFiles = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (File file : files) {
            try {
                String code = file.getName().replace(".json", "");
                JsonNode root = objectMapper.readTree(file);
                List<StockDaily> batch = new ArrayList<>();

                for (JsonNode node : root) {
                    try {
                        StockDaily d = new StockDaily();
                        d.setStockCode(code);
                        String dateStr = node.get("date").asText();
                        // 兼容 datetime 和 string
                        if (dateStr.contains("T")) {
                            dateStr = dateStr.substring(0, 10);
                        }
                        d.setTradeDate(LocalDate.parse(dateStr, fmt));
                        d.setOpen(BigDecimal.valueOf(node.get("open").asDouble()));
                        d.setHigh(BigDecimal.valueOf(node.get("high").asDouble()));
                        d.setLow(BigDecimal.valueOf(node.get("low").asDouble()));
                        d.setClose(BigDecimal.valueOf(node.get("close").asDouble()));
                        d.setVolume(BigDecimal.valueOf(node.get("volume").asLong()));
                        d.setAmount(node.has("amount") ? BigDecimal.valueOf(node.get("amount").asDouble()) : BigDecimal.ZERO);
                        batch.add(d);
                    } catch (Exception e) {
                        // skip bad row
                    }
                }

                if (!batch.isEmpty()) {
                    repository.saveAll(batch);
                    totalRecords += batch.size();
                    totalStocks++;
                }
            } catch (Exception e) {
                errors++;
                errorFiles.add(file.getName());
                log.error("导入失败 {}: {}", file.getName(), e.getMessage());
            }

            if (totalStocks % 100 == 0) {
                log.info("批量导入进度: {}只股票, {}条记录", totalStocks, totalRecords);
            }
        }

        result.put("stocksImported", totalStocks);
        result.put("recordsImported", totalRecords);
        result.put("errors", errors);
        result.put("errorFiles", errorFiles);
        log.info("批量导入完成: {}只股票, {}条记录, {}错误", totalStocks, totalRecords, errors);
        return result;
    }
}
