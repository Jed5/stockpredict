package com.stock.service;

import com.stock.config.EmailConfig;
import com.stock.model.StockRecommend;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final EmailConfig emailConfig;
    private JavaMailSender mailSender;

    public EmailService(EmailConfig emailConfig) {
        this.emailConfig = emailConfig;
    }

    @PostConstruct
    public void init() {
        if (!emailConfig.isEnabled() || emailConfig.getHost() == null) {
            log.info("[邮件] 邮件服务未启用（spring.mail.enabled=false 或未配置）");
            return;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(emailConfig.getHost());
        sender.setPort(emailConfig.getPort());
        sender.setUsername(emailConfig.getUsername());
        sender.setPassword(emailConfig.getPassword());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
        this.mailSender = sender;
        log.info("[邮件] 邮件服务已初始化: {}:{}", emailConfig.getHost(), emailConfig.getPort());
    }

    @Async
    public void sendDailyRecommendations(List<StockRecommend> recommendations, String toEmail) {
        if (!emailConfig.isEnabled() || mailSender == null) {
            log.warn("[邮件] 邮件服务未启用，跳过发送");
            return;
        }
        if (recommendations == null || recommendations.isEmpty()) {
            log.info("[邮件] 今日无推荐，跳过发送");
            return;
        }

        try {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            StringBuilder body = new StringBuilder();
            body.append("📈 A股智能选股系统 - 每日推荐报告\n");
            body.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            body.append("日期: ").append(date).append("\n");
            body.append("推荐总数: ").append(recommendations.size()).append("\n\n");

            long buyCount = recommendations.stream().filter(r -> "BUY".equals(r.getSignalType())).count();
            long sellCount = recommendations.stream().filter(r -> "SELL".equals(r.getSignalType())).count();
            long holdCount = recommendations.stream().filter(r -> "HOLD".equals(r.getSignalType())).count();

            body.append("【买入信号】").append(buyCount).append("只\n");
            for (StockRecommend r : recommendations) {
                if ("BUY".equals(r.getSignalType())) {
                    body.append(String.format("  ✅ %s(%s) 置信度:%s%% 目标价:¥%.2f 止损:¥%.2f\n",
                            r.getStockName(), r.getStockCode(),
                            r.getConfidenceScore().toString(),
                            r.getTargetPrice(), r.getStopLoss()));
                }
            }

            body.append("\n【卖出信号】").append(sellCount).append("只\n");
            for (StockRecommend r : recommendations) {
                if ("SELL".equals(r.getSignalType())) {
                    body.append(String.format("  🔻 %s(%s) 置信度:%s%% 目标价:¥%.2f 止损:¥%.2f\n",
                            r.getStockName(), r.getStockCode(),
                            r.getConfidenceScore().toString(),
                            r.getTargetPrice(), r.getStopLoss()));
                }
            }

            body.append("\n【持有信号】").append(holdCount).append("只\n");
            for (StockRecommend r : recommendations) {
                if ("HOLD".equals(r.getSignalType())) {
                    body.append(String.format("  ⏸ %s(%s) 置信度:%s%%\n",
                            r.getStockName(), r.getStockCode(),
                            r.getConfidenceScore().toString()));
                }
            }

            body.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            body.append("本报告由A股智能选股系统自动生成\n");
            body.append("仅供参考，不构成投资建议\n");

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailConfig.getUsername());
            message.setTo(toEmail);
            message.setSubject("【" + date + "】A股智能选股每日推荐");
            message.setText(body.toString());
            mailSender.send(message);
            log.info("[邮件] 发送成功: {} -> {} ({}条推荐)", emailConfig.getUsername(), toEmail, recommendations.size());
        } catch (Exception e) {
            log.error("[邮件] 发送失败: {}", e.getMessage());
        }
    }
}
