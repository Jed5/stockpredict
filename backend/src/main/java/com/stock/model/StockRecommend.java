package com.stock.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_recommend")
public class StockRecommend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "recommend_date")
    private LocalDate recommendDate;

    @Column(name = "signal_type", length = 20)
    private String signalType; // BUY, SELL, HOLD

    @Column(name = "signal_source", length = 50)
    private String signalSource; // TECHNICAL, ML, BOTH

    @Column(name = "confidence_score")
    private Double confidenceScore; // 0-100

    @Column(name = "target_price")
    private Double targetPrice;

    @Column(name = "stop_loss")
    private Double stopLoss;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public LocalDate getRecommendDate() { return recommendDate; }
    public void setRecommendDate(LocalDate recommendDate) { this.recommendDate = recommendDate; }
    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }
    public String getSignalSource() { return signalSource; }
    public void setSignalSource(String signalSource) { this.signalSource = signalSource; }
    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }
    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }
    public Double getStopLoss() { return stopLoss; }
    public void setStopLoss(Double stopLoss) { this.stopLoss = stopLoss; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
