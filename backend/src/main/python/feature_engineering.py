"""
特征工程模块
从原始 OHLCV 数据计算模型所需特征
"""

import numpy as np
import pandas as pd


def compute_rsi(prices, period=14):
    """
    计算 RSI 相对强弱指标
    prices: 收盘价序列
    period: 计算周期（默认14天）
    返回: RSI 值 (0-100)
    """
    if len(prices) < period + 1:
        return 50.0

    deltas = np.diff(prices)
    gains = np.where(deltas > 0, deltas, 0)
    losses = np.where(deltas < 0, -deltas, 0)

    avg_gain = np.mean(gains[-period:])
    avg_loss = np.mean(losses[-period:])

    if avg_loss == 0:
        return 100.0

    rs = avg_gain / avg_loss
    rsi = 100 - (100 / (1 + rs))
    return rsi


def compute_macd(prices, fast=12, slow=26, signal=9):
    """
    计算 MACD
    返回: (dif, dea, bar)
    """
    if len(prices) < slow:
        return 0.0, 0.0, 0.0

    # 计算 EMA
    def calc_ema(p, period):
        ema = [p[0]]
        multiplier = 2 / (period + 1)
        for price in p[1:]:
            ema.append((price - ema[-1]) * multiplier + ema[-1])
        return np.array(ema)

    ema_fast = calc_ema(prices, fast)
    ema_slow = calc_ema(prices, slow)

    dif = ema_fast - ema_slow

    # DEA 是 DIF 的 EMA
    ema_dif = calc_ema(dif, signal)
    dea = ema_dif[-1] if len(ema_dif) > 0 else 0
    bar = (dif[-1] - dea) * 2 if len(dif) > 0 else 0

    return dif[-1], dea, bar


def extract_features(df):
    """
    从 DataFrame 提取特征矩阵
    df 需包含列: open, high, low, close, volume
    返回: numpy array (n_samples, n_features)
    特征顺序: [open, high, low, close, volume, ma5, ma10, ma20, rsi]
    """
    close = df["close"].values.astype(float)
    open_prices = df["open"].values.astype(float)
    high = df["high"].values.astype(float)
    low = df["low"].values.astype(float)
    volume = df["volume"].values.astype(float)

    n = len(close)
    features = np.zeros((n, 9))

    for i in range(n):
        features[i, 0] = open_prices[i]
        features[i, 1] = high[i]
        features[i, 2] = low[i]
        features[i, 3] = close[i]
        features[i, 4] = volume[i]

        # MA
        if i >= 4:
            features[i, 5] = np.mean(close[max(0, i-4):i+1])
        else:
            features[i, 5] = close[i]

        if i >= 9:
            features[i, 6] = np.mean(close[max(0, i-9):i+1])
        else:
            features[i, 6] = close[i]

        if i >= 19:
            features[i, 7] = np.mean(close[max(0, i-19):i+1])
        else:
            features[i, 7] = close[i]

        # RSI
        features[i, 8] = compute_rsi(close[:i+1], 14)

    return features


def create_sequences(features, labels, seq_len=60):
    """
    构建时序样本
    features: (n_samples, n_features)
    labels:   (n_samples,)  0/1 涨跌标签
    seq_len:  历史窗口长度

    返回:
    X: (n_valid_samples, seq_len, n_features)
    y: (n_valid_samples,)
    """
    X, y = [], []
    for i in range(seq_len, len(features)):
        X.append(features[i - seq_len:i])
        y.append(labels[i])

    return np.array(X), np.array(y)
