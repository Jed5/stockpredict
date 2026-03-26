package com.stock.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 股票信息映射服务
 * 根据股票代码获取股票名称
 */
@Service
public class StockInfoService {

    // 本地缓存股票名称（演示用，实际可从数据库或API获取）
    private static final Map<String, String> STOCK_NAME_CACHE = new HashMap<>();
    
    // 常用股票名称映射（示例）
    static {
        STOCK_NAME_CACHE.put("600519", "贵州茅台");
        STOCK_NAME_CACHE.put("000858", "五粮液");
        STOCK_NAME_CACHE.put("600036", "招商银行");
        STOCK_NAME_CACHE.put("601318", "中国平安");
        STOCK_NAME_CACHE.put("000333", "美的集团");
        STOCK_NAME_CACHE.put("300750", "宁德时代");
        STOCK_NAME_CACHE.put("002594", "比亚迪");
        STOCK_NAME_CACHE.put("600276", "恒瑞医药");
        STOCK_NAME_CACHE.put("601888", "中国中免");
        STOCK_NAME_CACHE.put("600887", "伊利股份");
        STOCK_NAME_CACHE.put("000002", "万科A");
        STOCK_NAME_CACHE.put("600030", "中信证券");
        STOCK_NAME_CACHE.put("601166", "兴业银行");
        STOCK_NAME_CACHE.put("600016", "民生银行");
        STOCK_NAME_CACHE.put("601398", "工商银行");
        STOCK_NAME_CACHE.put("601939", "建设银行");
        STOCK_NAME_CACHE.put("601288", "农业银行");
        STOCK_NAME_CACHE.put("601988", "中国银行");
        STOCK_NAME_CACHE.put("600000", "浦发银行");
        STOCK_NAME_CACHE.put("601328", "交通银行");
    }

    /**
     * 根据股票代码获取股票名称
     */
    public String getStockName(String stockCode) {
        // 先从本地缓存获取
        String name = STOCK_NAME_CACHE.get(stockCode);
        if (name != null) {
            return name;
        }
        
        // 尝试通过Python akshare获取
        name = fetchStockNameFromAKShare(stockCode);
        if (name != null && !name.isEmpty()) {
            STOCK_NAME_CACHE.put(stockCode, name);
            return name;
        }
        
        // 未知股票返回代码本身
        return stockCode;
    }

    /**
     * 通过AKShare获取股票名称
     */
    private String fetchStockNameFromAKShare(String stockCode) {
        try {
            String pythonScript = """
                import akshare as ak
                import sys
                try:
                    if len(sys.argv) > 1:
                        code = sys.argv[1]
                        if code.startswith('6'):
                            symbol = "sh" + code
                        else:
                            symbol = "sz" + code
                        df = ak.stock_zh_a_spot_em()
                        result = df[df['代码'] == code]
                        if not result.empty:
                            print(result.iloc[0]['名称'])
                        else:
                            print('')
                except Exception as e:
                    print('')
                """;
            
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", pythonScript, stockCode);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String name = reader.readLine();
            process.waitFor();
            
            return name != null ? name.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 批量获取股票名称
     */
    public Map<String, String> getStockNames(java.util.List<String> stockCodes) {
        Map<String, String> result = new HashMap<>();
        for (String code : stockCodes) {
            result.put(code, getStockName(code));
        }
        return result;
    }
}
