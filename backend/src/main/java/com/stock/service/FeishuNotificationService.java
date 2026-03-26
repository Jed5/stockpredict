package com.stock.service;

import com.stock.config.FeishuConfig;
import com.stock.model.StockRecommend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class FeishuNotificationService {

    private static final Logger log = LoggerFactory.getLogger(FeishuNotificationService.class);
    private final FeishuConfig feishuConfig;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public FeishuNotificationService(FeishuConfig feishuConfig) {
        this.feishuConfig = feishuConfig;
    }

    @Async
    public void sendDailyRecommendations(List<StockRecommend> recommendations) {
        if (!feishuConfig.isEnabled() || feishuConfig.getWebhook() == null) {
            log.warn("[飞书] 推送未启用，请检查 stock.feishu.enabled 和 stock.feishu.webhook 配置");
            return;
        }
        if (recommendations == null || recommendations.isEmpty()) {
            log.info("[飞书] 今日无推荐，跳过推送");
            return;
        }

        try {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

            long buyCount = recommendations.stream().filter(r -> "BUY".equals(r.getSignalType())).count();
            long sellCount = recommendations.stream().filter(r -> "SELL".equals(r.getSignalType())).count();
            long holdCount = recommendations.stream().filter(r -> "HOLD".equals(r.getSignalType())).count();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📈 **A股智能选股 - 每日推荐 %s**\n\n", date));
            sb.append(String.format("今日推荐总数：**%d** 只  ✅买入 %d  |  🔻卖出 %d  |  ⏸持有 %d\n\n",
                    recommendations.size(), buyCount, sellCount, holdCount));

            // 买入信号
            if (buyCount > 0) {
                sb.append("━━━━━━━━━━\n🟢 **【买入信号】**\n\n");
                for (StockRecommend r : recommendations) {
                    if (!"BUY".equals(r.getSignalType())) continue;
                    sb.append(String.format("✅ **%s(%s)**  置信度 %s%%\n",
                            r.getStockName(), r.getStockCode(), r.getConfidenceScore().toString()));
                    if (r.getTargetPrice() != null) {
                        sb.append(String.format("   目标价 ¥%.2f  |  止损 ¥%.2f\n", r.getTargetPrice(), r.getStopLoss()));
                    }
                    if (r.getReason() != null) {
                        sb.append(String.format("   📝 %s\n", r.getReason().replaceAll("[\\[\\]]", "").split(" \\| ")[0]));
                    }
                }
                sb.append("\n");
            }

            // 卖出信号
            if (sellCount > 0) {
                sb.append("━━━━━━━━━━\n🔴 **【卖出信号】**\n\n");
                for (StockRecommend r : recommendations) {
                    if (!"SELL".equals(r.getSignalType())) continue;
                    sb.append(String.format("🔻 **%s(%s)**  置信度 %s%%\n",
                            r.getStockName(), r.getStockCode(), r.getConfidenceScore().toString()));
                    if (r.getTargetPrice() != null) {
                        sb.append(String.format("   目标价 ¥%.2f  |  止损 ¥%.2f\n", r.getTargetPrice(), r.getStopLoss()));
                    }
                }
                sb.append("\n");
            }

            // 持有信号
            if (holdCount > 0 && buyCount == 0 && sellCount == 0) {
                sb.append("━━━━━━━━━━\n🔵 **【持有/观望】**\n\n");
                for (StockRecommend r : recommendations) {
                    if (!"HOLD".equals(r.getSignalType())) continue;
                    sb.append(String.format("⏸ %s(%s)  置信度 %s%%\n",
                            r.getStockName(), r.getStockCode(), r.getConfidenceScore().toString()));
                }
                sb.append("\n");
            }

            sb.append("━━━━━━━━━━\n");
            sb.append("⚠️ 仅供参考，不构成投资建议\n");
            sb.append("🤖 A股智能选股系统自动推送");

            String payload = String.format("""
                {
                    "msg_type": "text",
                    "content": {
                        "text": %s
                    }
                }
                """, toJsonString(sb.toString()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feishuConfig.getWebhook()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[飞书] 推送成功: {} 条推荐", recommendations.size());
            } else {
                log.error("[飞书] 推送失败: HTTP {} - {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("[飞书] 推送异常: {}", e.getMessage());
        }
    }

    private String toJsonString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "").replace("\t", "\\t");
    }
}
