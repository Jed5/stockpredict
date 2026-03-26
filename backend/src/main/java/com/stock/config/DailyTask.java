package com.stock.config;

import com.stock.model.StockRecommend;
import com.stock.service.FeishuNotificationService;
import com.stock.service.StockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class DailyTask {

    private static final Logger log = LoggerFactory.getLogger(DailyTask.class);
    private static final String SCAN_SCRIPT = "/tmp/daily_scan.py";
    private static final String DATA_DIR = "/tmp/stock_data_a_2y";

    private final FeishuNotificationService feishuNotificationService;

    public DailyTask(FeishuNotificationService feishuNotificationService) {
        this.feishuNotificationService = feishuNotificationService;
    }

    /**
     * 每天早上9:15执行全市场扫描
     */
    @Scheduled(cron = "0 15 9 * * *")
    public void dailyStockScan() {
        log.info("[DailyTask] 开始全市场扫描: " + LocalDateTime.now());
        try {
            // 运行Python扫描脚本
            ProcessBuilder pb = new ProcessBuilder(
                "python3", SCAN_SCRIPT
            );
            pb.environment().put("DATA_DIR", DATA_DIR);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("[Scan] {}", line);
                }
            }

            boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[DailyTask] 扫描超时");
                return;
            }

            String result = output.toString();
            log.info("[DailyTask] 扫描完成, exitCode={}", process.exitValue());
            log.info("[DailyTask] 扫描输出:\n{}", result);

            // 解析输出中的 BUY/SELL 信号，构造 StockRecommend 列表
            List<StockRecommend> recommendations = parseScanOutput(result);
            log.info("[DailyTask] 解析出 BUY:{} SELL:{} 信号",
                    recommendations.stream().filter(r -> "BUY".equals(r.getSignalType())).count(),
                    recommendations.stream().filter(r -> "SELL".equals(r.getSignalType())).count());

            // 发送飞书推送
            if (!recommendations.isEmpty()) {
                feishuNotificationService.sendDailyRecommendations(recommendations);
            }

        } catch (Exception e) {
            log.error("[DailyTask] 全市场扫描异常: {}", e.getMessage(), e);
        }
    }

    private List<StockRecommend> parseScanOutput(String output) {
        List<StockRecommend> results = new ArrayList<>();
        String[] lines = output.split("\n");
        String currentSection = null;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("BUY 信号:") || line.startsWith("✅")) {
                currentSection = "BUY";
            } else if (line.startsWith("SELL 信号:") || line.startsWith("🔻")) {
                currentSection = "SELL";
            } else if (line.startsWith("--- TOP")) {
                currentSection = null;
            }

            if (currentSection != null && (line.startsWith("✅") || line.startsWith("🔻"))) {
                try {
                    String[] parts = line.substring(line.indexOf(" ") + 1).split("\\s+");
                    if (parts.length >= 3) {
                        String code = parts[0].replace("¥", "").trim();
                        String priceStr = parts[1].replace("¥", "").trim();
                        double price = Double.parseDouble(priceStr);
                        String scoreStr = Arrays.stream(parts)
                                .filter(p -> p.startsWith("评分:"))
                                .findFirst().orElse("0").replace("评分:", "").trim();
                        int score = Integer.parseInt(scoreStr);

                        StockRecommend rec = new StockRecommend();
                        rec.setStockCode(code);
                        rec.setSignalType(currentSection);
                        rec.setSignalSource("SCAN");
                        rec.setConfidenceScore(Math.min(100, Math.abs(score) * 10.0));
                        rec.setRecommendDate(LocalDate.now());
                        rec.setTargetPrice(price * (currentSection.equals("BUY") ? 1.15 : 0.90));
                        rec.setStopLoss(price * (currentSection.equals("BUY") ? 0.95 : 1.05));
                        rec.setReason(line.length() > 200 ? line.substring(0, 200) : line);
                        results.add(rec);
                    }
                } catch (Exception e) {
                    // skip malformed lines
                }
            }
        }
        return results;
    }
}
