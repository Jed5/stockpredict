"""
LSTM 股票预测模型
模型结构: Input → LSTM(64) → Dropout → LSTM(32) → Dropout → FC(1) → Sigmoid
输入特征: [开盘价, 最高价, 最低价, 收盘价, 成交量, MA5, MA10, MA20, RSI]
预测目标: T+1 日收盘价涨跌 (1=涨, 0=跌)
"""

import torch
import torch.nn as nn


class LSTMModel(nn.Module):
    """
    LSTM 股票分类模型
    输入: (batch_size, seq_len, input_size)  三维张量
    输出: (batch_size, 1)  涨跌幅概率
    """
    def __init__(self, input_size=9, hidden_size=64, num_layers=2, dropout=0.2):
        super(LSTMModel, self).__init__()

        self.hidden_size = hidden_size
        self.num_layers = num_layers

        # LSTM 层
        self.lstm = nn.LSTM(
            input_size=input_size,
            hidden_size=hidden_size,
            num_layers=num_layers,
            batch_first=True,
            dropout=dropout if num_layers > 1 else 0,
            bidirectional=False  # 单向 LSTM
        )

        # 全连接层
        self.fc1 = nn.Linear(hidden_size, 32)
        self.relu = nn.ReLU()
        self.dropout = nn.Dropout(dropout)
        self.fc2 = nn.Linear(32, 1)
        self.sigmoid = nn.Sigmoid()

    def forward(self, x):
        """
        x: (batch_size, seq_len, input_size)
        """
        # LSTM 输出: (batch_size, seq_len, hidden_size)
        out, _ = self.lstm(x)

        # 取最后一个时间步的输出: (batch_size, hidden_size)
        out = out[:, -1, :]

        # 全连接层
        out = self.fc1(out)    # (batch_size, 32)
        out = self.relu(out)
        out = self.dropout(out)
        out = self.fc2(out)    # (batch_size, 1)
        out = self.sigmoid(out)  # (batch_size, 1)

        return out

    def predict(self, x):
        """
        推理接口
        x: numpy array (seq_len, input_size)
        返回: 上涨概率 float
        """
        self.eval()
        with torch.no_grad():
            x_tensor = torch.FloatTensor(x).unsqueeze(0)  # (1, seq_len, input_size)
            prob = self(x_tensor).item()
        return prob


def get_model(input_size=9, hidden_size=64, num_layers=2, dropout=0.2):
    """工厂方法，创建模型实例"""
    return LSTMModel(input_size, hidden_size, num_layers, dropout)
