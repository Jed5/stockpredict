#!/usr/bin/env python3
"""
直接通过 JavaScript/urllib 模拟 HTTP 请求来获取数据并存入
实际上这里通过直接调用 akshare + H2 数据库 JDBC 来插入数据
"""
import akshare as ak
import json
import sqlite3

def main():
    code = "300059"
    start = "2026-01-01"
    end = "2026-03-26"

    symbol = ("sh" + code) if code.startswith("6") else ("sz" + code)
    print(f"获取 {symbol} 数据...")
    df = ak.stock_zh_a_daily(symbol=symbol, adjust="qfq")
    df["date"] = df["date"].astype(str)

    records = []
    for _, row in df.iterrows():
        date_str = str(row["date"])
        if start <= date_str <= end:
            records.append({
                "date": date_str,
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
                "volume": float(row["volume"]),
                "amount": float(row["amount"]) if "amount" in row else 0
            })

    print(f"获取到 {len(records)} 条数据")
    with open(f"/tmp/{code}_data.json", "w") as f:
        json.dump(records, f)
    print(f"已保存到 /tmp/{code}_data.json")

if __name__ == "__main__":
    main()
