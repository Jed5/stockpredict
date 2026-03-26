package com.stock.repository;

import com.stock.model.StockRecommend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StockRecommendRepository extends JpaRepository<StockRecommend, Long> {
    List<StockRecommend> findByRecommendDateOrderByConfidenceScoreDesc(LocalDate date);
    List<StockRecommend> findBySignalTypeAndRecommendDate(String signalType, LocalDate date);
}
