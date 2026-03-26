package com.stock.repository;

import com.stock.model.StockDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockDailyRepository extends JpaRepository<StockDaily, Long> {
    List<StockDaily> findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
        String stockCode, LocalDate startDate, LocalDate endDate);

    Optional<StockDaily> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

    @Query("SELECT DISTINCT s.stockCode FROM StockDaily s")
    List<String> findAllStockCodes();

    @Query("SELECT s FROM StockDaily s WHERE s.tradeDate = :date ORDER BY s.amount DESC")
    List<StockDaily> findTopByDate(@Param("date") LocalDate date);
}
