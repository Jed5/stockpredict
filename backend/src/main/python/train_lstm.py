"""
LSTM 模型训练脚本
用法: python3 train_lstm.py [stock_code] [start_date] [end_date]
示例: python3 train_lstm.py 600519 20200101 20240326

训练数据来源: AKShare（东方财富）
模型保存: models/{stock_code}_lstm.pth
标准化器: models/{stock_code}_scaler.pkl
"""

import sys
import os
import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, TensorDataset
from sklearn.preprocessing import MinMaxScaler
import pickle
import warnings

warnings.filterwarnings("ignore")

# 导入本地模块
from lstm_model import LSTMModel
from feature_engineering import extract_features, create_sequences

# ============ 配置 ============
SEQ_LEN = 60        # 历史窗口长度（天数）
HIDDEN_SIZE = 64    # LSTM 隐藏层大小
NUM_LAYERS = 2      # LSTM 层数
DROPOUT = 0.2       # Dropout 比例
EPOCHS = 50         # 训练轮数
BATCH_SIZE = 32     # 批次大小
LEARNING_RATE = 0.001
MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")


def load_stock_data(stock_code, start_date, end_date):
    """
    通过 AKShare 获取股票数据
    使用 stock_zh_a_daily 接口（返回包含 date, open, high, low, close, volume 等列）
    """
    try:
        import akshare as ak

        # 上证股票前缀 sh，深证前缀 sz
        symbol = f"sh{stock_code}" if stock_code.startswith("6") else f"sz{stock_code}"

        # stock_zh_a_daily 返回: date, open, high, low, close, volume, amount, outstanding_share, turnover
        df = ak.stock_zh_a_daily(symbol=symbol, adjust="qfq")

        # 转换日期字符串为 datetime
        df["date"] = pd.to_datetime(df["date"])

        # 按日期升序排列
        df = df.sort_values("date").reset_index(drop=True)

        # 按日期范围过滤
        start_dt = pd.to_datetime(start_date, format="%Y%m%d")
        end_dt = pd.to_datetime(end_date, format="%Y%m%d")
        df = df[(df["date"] >= start_dt) & (df["date"] <= end_dt)]

        print(f"[数据加载成功] {stock_code} 共 {len(df)} 条记录")
        print(f"  日期范围: {df['date'].min().date()} ~ {df['date'].max().date()}")
        return df

    except Exception as e:
        print(f"[数据加载失败] {type(e).__name__}: {e}")
        sys.exit(1)


def load_csv_data(csv_path):
    """
    从本地 CSV 加载数据（备选方式）
    """
    df = pd.read_csv(csv_path)
    df["date"] = pd.to_datetime(df["date"])
    df = df.sort_values("date").reset_index(drop=True)
    print(f"[CSV加载成功] 共 {len(df)} 条记录")
    return df


def create_labels(close_prices):
    """
    生成标签: T+1日收盘价相比T日上涨=1，下跌=0
    """
    labels = np.zeros(len(close_prices))
    for i in range(len(close_prices) - 1):
        labels[i] = 1 if close_prices[i + 1] > close_prices[i] else 0
    labels[-1] = labels[-2]  # 最后一天复制前一天的标签
    return labels


def train_model(stock_code, start_date, end_date, csv_path=None):
    """
    完整训练流程
    """
    os.makedirs(MODEL_DIR, exist_ok=True)

    # ===== 1. 加载数据 =====
    if csv_path and os.path.exists(csv_path):
        df = load_csv_data(csv_path)
    else:
        df = load_stock_data(stock_code, start_date, end_date)

    if len(df) < SEQ_LEN + 30:
        print(f"[数据不足] 需要至少 {SEQ_LEN + 30} 条记录，当前 {len(df)} 条")
        return False

    # ===== 2. 特征工程 =====
    print("[特征提取] 计算 MA、RSI 等指标...")
    features = extract_features(df)  # (n, 9)
    close_prices = df["close"].values
    labels = create_labels(close_prices)  # (n,)

    # ===== 3. 数据标准化 =====
    scaler = MinMaxScaler()
    features_scaled = scaler.fit_transform(features)

    # ===== 4. 构建序列 =====
    X, y = create_sequences(features_scaled, labels, SEQ_LEN)
    print(f"[数据集] X shape: {X.shape}, y shape: {y.shape}")

    # 转换为 PyTorch 张量
    X_tensor = torch.FloatTensor(X)
    y_tensor = torch.FloatTensor(y).unsqueeze(1)

    dataset = TensorDataset(X_tensor, y_tensor)
    train_loader = DataLoader(dataset, batch_size=BATCH_SIZE, shuffle=True)

    # ===== 5. 模型 =====
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[设备] 使用: {device}")

    model = LSTMModel(input_size=9, hidden_size=HIDDEN_SIZE,
                      num_layers=NUM_LAYERS, dropout=DROPOUT).to(device)

    criterion = nn.BCELoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE)

    # ===== 6. 训练循环 =====
    print(f"[训练] 开始训练 {EPOCHS} 轮...")
    model.train()
    for epoch in range(EPOCHS):
        total_loss = 0
        correct = 0
        total = 0

        for batch_X, batch_y in train_loader:
            batch_X = batch_X.to(device)
            batch_y = batch_y.to(device)

            optimizer.zero_grad()
            outputs = model(batch_X)
            loss = criterion(outputs, batch_y)
            loss.backward()
            optimizer.step()

            total_loss += loss.item()
            predicted = (outputs >= 0.5).float()
            correct += (predicted == batch_y).sum().item()
            total += batch_y.size(0)

        acc = correct / total
        avg_loss = total_loss / len(train_loader)

        if (epoch + 1) % 5 == 0 or epoch == 0:
            print(f"  Epoch {epoch+1:3d}/{EPOCHS} | Loss: {avg_loss:.4f} | Acc: {acc:.4f}")

    # ===== 7. 评估（用最后30天做验证）=====
    model.eval()
    val_size = 30
    X_val = X_tensor[-val_size:].to(device)
    y_val = y_tensor[-val_size:].to(device)

    with torch.no_grad():
        val_out = model(X_val)
        val_pred = (val_out >= 0.5).float()
        val_acc = (val_pred == y_val).float().mean().item()

    print(f"[验证准确率] 最后{val_size}天: {val_acc:.4f}")

    # ===== 8. 保存模型 =====
    model_path = os.path.join(MODEL_DIR, f"{stock_code}_lstm.pth")
    scaler_path = os.path.join(MODEL_DIR, f"{stock_code}_scaler.pkl")

    torch.save(model.state_dict(), model_path)
    with open(scaler_path, "wb") as f:
        pickle.dump(scaler, f)

    print(f"[保存] 模型: {model_path}")
    print(f"[保存] 标准化器: {scaler_path}")
    print(f"[完成] 训练成功!")
    return True


if __name__ == "__main__":
    # 默认参数
    stock_code = "600519"
    start_date = "20200101"
    end_date = "20240326"
    csv_path = None

    # 命令行参数
    if len(sys.argv) >= 2:
        stock_code = sys.argv[1]
    if len(sys.argv) >= 4:
        start_date = sys.argv[2]
        end_date = sys.argv[3]
    if len(sys.argv) >= 5:
        csv_path = sys.argv[4]

    print(f"=" * 50)
    print(f"股票代码: {stock_code}")
    print(f"数据范围: {start_date} ~ {end_date}")
    print(f"=" * 50)

    success = train_model(stock_code, start_date, end_date, csv_path)

    if not success:
        print("[失败] 训练未完成")
        sys.exit(1)
