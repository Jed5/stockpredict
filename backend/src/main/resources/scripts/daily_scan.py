#!/usr/bin/env python3
"""
每日全市场扫描 - 从本地JSON数据读取，技术分析选股
用法: python3 daily_scan.py
"""
import json, os, sys, time
from datetime import datetime

DATA_DIR = "/tmp/stock_data_a_2y"

def rsi(prices, period=14):
    if len(prices) < period + 1:
        return 50.0
    gains, losses = [], []
    for i in range(1, len(prices)):
        d = prices[i] - prices[i-1]
        gains.append(max(d, 0))
        losses.append(max(-d, 0))
    avg_gain = sum(gains[-period:]) / period
    avg_loss = sum(losses[-period:]) / period
    if avg_loss == 0:
        return 100
    return 100 - (100 / (1 + avg_gain / avg_loss))

def ema(prices, n):
    if len(prices) < n:
        return sum(prices) / len(prices)
    k = 2 / (n + 1)
    e = sum(prices[:n]) / n
    for px in prices[n:]:
        e = px * k + e * (1 - k)
    return e

def macd(prices):
    if len(prices) < 35:
        return 0, 0, 0
    ef = ema(prices, 12)
    es = ema(prices, 26)
    dif = ef - es
    #dea = ema([dif]*9, 9)  # simplified
    return dif, 0, 0

def analyze_stock(data):
    """返回 (signal, score, reason, latest_price)"""
    if len(data) < 30:
        return None, 0, "", 0
    
    closes = [d['close'] for d in data]
    highs  = [d['high']  for d in data]
    lows   = [d['low']   for d in data]
    vols   = [d['volume'] for d in data]
    latest = closes[-1]
    
    n = len(closes)
    
    # MA
    ma5  = sum(closes[-5:]) / 5  if n >= 5  else sum(closes) / n
    ma10 = sum(closes[-10:]) / 10 if n >= 10 else ma5
    ma20 = sum(closes[-20:]) / 20 if n >= 20 else ma10
    ma60 = sum(closes[-60:]) / 60 if n >= 60 else ma20
    
    # RSI
    rsi14 = rsi(closes)
    
    # MACD
    dif, _, _ = macd(closes[-35:])
    
    # 布林带
    mean20 = sum(closes[-20:]) / 20
    std = (sum((c - mean20) ** 2 for c in closes[-20:]) / 20) ** 0.5
    boll_lower = mean20 - 2 * std
    boll_upper = mean20 + 2 * std
    boll_mid   = mean20
    
    # 评分
    score = 0
    reasons = []
    
    # 均线
    if ma5 > ma10 > ma20:
        score += 3; reasons.append(f"均线多头(+3)")
    elif ma5 < ma10 < ma20:
        score -= 3; reasons.append(f"均线空头(-3)")
    elif ma5 > ma20:
        score += 1; reasons.append(f"MA5>MA20(+1)")
    elif ma5 < ma20:
        score -= 1; reasons.append(f"MA5<MA20(-1)")
    
    # MACD
    if dif > 0:
        score += 2; reasons.append(f"DIF>0(+2)")
    else:
        score -= 2; reasons.append(f"DIF<0(-2)")
    
    # RSI
    if rsi14 < 30:
        score += 3; reasons.append(f"RSI({rsi14:.0f})超卖(+3)")
    elif rsi14 > 70:
        score -= 3; reasons.append(f"RSI({rsi14:.0f})超买(-3)")
    elif rsi14 < 40:
        score += 1; reasons.append(f"RSI偏低(+1)")
    elif rsi14 > 60:
        score -= 1; reasons.append(f"RSI偏高(-1)")
    
    # 布林带
    if latest < boll_lower:
        score += 3; reasons.append("布线下轨支撑(+3)")
    elif latest > boll_upper:
        score -= 3; reasons.append("布线上轨压力(-3)")
    
    # 成交量
    avg_vol5 = sum(vols[-5:]) / 5
    if vols[-1] > avg_vol5 * 1.5:
        score += 1; reasons.append("成交量放大(+1)")
    
    # 趋势（20日高低点）
    high20 = max(highs[-20:])
    low20  = min(lows[-20:])
    if latest > high20 * 0.97:
        score += 1; reasons.append("接近20日高点(+1)")
    if latest < low20 * 1.03:
        score += 1; reasons.append("接近20日低点支撑(+1)")
    
    # 信号判定
    if score >= 5:
        signal = "BUY"
    elif score <= -5:
        signal = "SELL"
    else:
        signal = "HOLD"
    
    reason = " | ".join(reasons[:4])
    return signal, score, reason, latest

def main():
    files = [f for f in os.listdir(DATA_DIR) if f.endswith(".json")
             and f.replace(".json","") not in ("done","failed","meta")]
    
    print(f"开始全市场扫描 {len(files)} 只股票...")
    
    buys, sells = [], []
    start = time.time()
    
    for i, fname in enumerate(files):
        code = fname.replace(".json","")
        try:
            with open(os.path.join(DATA_DIR, fname)) as f:
                data = json.load(f)
            if not data:
                continue
            data.sort(key=lambda x: x['date'])
            recent = [d for d in data if d['date'] >= '2024-03-26']
            if not recent:
                continue
            
            signal, score, reason, price = analyze_stock(recent[-60:])
            if signal == "BUY":
                buys.append({"code": code, "price": price, "score": score, "reason": reason})
            elif signal == "SELL":
                sells.append({"code": code, "price": price, "score": score, "reason": reason})
        except Exception as e:
            pass
        
        if (i+1) % 500 == 0:
            elapsed = time.time() - start
            eta = elapsed / (i+1) * (len(files) - i - 1)
            print(f"  进度 {i+1}/{len(files)}  BUY:{len(buys)}  SELL:{len(sells)}  ETA:{eta:.0f}s")

    buys.sort(key=lambda x: -x['score'])
    sells.sort(key=lambda x: x['score'])
    
    print(f"\n===== 扫描完成 ({time.time()-start:.1f}秒) =====")
    print(f"BUY 信号: {len(buys)} 只")
    print(f"SELL 信号: {len(sells)} 只")
    print(f"\n--- TOP 买入信号 ---")
    for b in buys[:10]:
        print(f"  ✅ {b['code']} ¥{b['price']:.2f} 评分:{b['score']} {b['reason'][:60]}")
    print(f"\n--- TOP 卖出信号 ---")
    for s in sells[:10]:
        print(f"  🔻 {s['code']} ¥{s['price']:.2f} 评分:{s['score']} {s['reason'][:60]}")
    
    return buys[:20], sells[:20]

if __name__ == "__main__":
    main()
