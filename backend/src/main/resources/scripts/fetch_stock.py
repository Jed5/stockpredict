#!/usr/bin/env python3
"""
AKShare 股票/指数数据获取脚本

股票用法: python3 fetch_stock.py <代码> <开始日期> <结束日期>
  示例: python3 fetch_stock.py 600519 2026-01-01 2026-03-26

指数用法: python3 fetch_stock.py <代码> <市场> <开始日期> <结束日期>
  示例: python3 fetch_stock.py 000001 sh 2026-01-01 2026-03-26
"""

import pandas as pd
import akshare as ak
import sys
import json
from datetime import datetime

# 指数代码映射
INDEX_MAP = {
    "000001": ("sh", "上证指数"),
    "399001": ("sz", "深证成指"),
    "399006": ("sz", "创业板指"),
    "000300": ("sh", "沪深300"),
    "000016": ("sh", "上证50"),
    "000688": ("sh", "科创50"),
}

try:
    code = sys.argv[1]

    # 判断是否传入市场前缀（指数有4个参数）
    if len(sys.argv) == 5:
        # 指数: code market start end
        market = sys.argv[2]
        start_str = sys.argv[3]
        end_str = sys.argv[4]
    else:
        # 个股: code start end（无市场参数）
        start_str = sys.argv[2]
        end_str = sys.argv[3]
        market = None

    start_dt = datetime.strptime(start_str, "%Y-%m-%d").date()
    end_dt   = datetime.strptime(end_str,   "%Y-%m-%d").date()

    # 判断是股票还是指数
    if code in INDEX_MAP:
        prefix, name = INDEX_MAP[code]
        symbol = prefix + code
        df = ak.stock_zh_index_daily(symbol=symbol)
        df = df[df["date"] >= start_dt]
        df = df[df["date"] <= end_dt]
    else:
        # 个股
        symbol = ("sh" + code) if code.startswith("6") else ("sz" + code)
        df = ak.stock_zh_a_daily(symbol=symbol, adjust="qfq")
        df = df[df["date"] >= start_dt]
        df = df[df["date"] <= end_dt]

    result = []
    for _, row in df.iterrows():
        result.append({
            "date":   str(row["date"]),
            "open":   float(row["open"]),
            "high":   float(row["high"]),
            "low":    float(row["low"]),
            "close":  float(row["close"]),
            "volume": float(row["volume"]),
            "amount": float(row["amount"]) if "amount" in row and not pd.isna(row["amount"]) else 0
        })

    print(json.dumps(result, ensure_ascii=False))

except Exception as e:
    import traceback
    print("Error: " + str(e), file=sys.stderr)
    traceback.print_exc()
    sys.exit(1)
