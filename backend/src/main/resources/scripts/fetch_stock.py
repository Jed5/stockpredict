#!/usr/bin/env python3
"""
AKShare 股票数据获取脚本
用法: python3 fetch_stock.py <股票代码> <开始日期> <结束日期>
示例: python3 fetch_stock.py 600519 2026-01-01 2026-03-26
"""

import akshare as ak
import sys
import json

code = sys.argv[1]
start = sys.argv[2]
end = sys.argv[3]

try:
    symbol = ("sh" + code) if code.startswith("6") else ("sz" + code)
    df = ak.stock_zh_a_daily(symbol=symbol, adjust="qfq")
    df["date"] = df["date"].astype(str)

    result = []
    for _, row in df.iterrows():
        date_str = str(row["date"])
        if start <= date_str <= end:
            result.append({
                "date": date_str,
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
                "volume": float(row["volume"]),
                "amount": float(row["amount"]) if "amount" in row else 0
            })

    print(json.dumps(result))

except Exception as e:
    print("Error: " + str(e), file=sys.stderr)
    sys.exit(1)
