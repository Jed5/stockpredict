# LSTM 股票预测模块

> 基于 PyTorch LSTM 模型的股价涨跌预测

## 目录结构

```
python/
├── requirements.txt          # Python 依赖
├── lstm_model.py             # LSTM 模型定义
├── feature_engineering.py    # 特征工程（MA/RSI/MACD）
├── train_lstm.py             # 模型训练脚本
├── predict_server.py         # Flask 推理 HTTP 服务
└── models/                   # 训练产出的模型文件
    ├── 600519_lstm.pth       # 模型权重
    └── 600519_scaler.pkl     # 标准化器
```

## 快速开始

### 1. 安装依赖

```bash
pip3 install torch numpy pandas scikit-learn flask akshare --break-system-packages
```

### 2. 训练模型

```bash
# 获取 600519（贵州茅台）近4年数据并训练
python3 train_lstm.py 600519 20200101 20240326

# 使用本地 CSV 数据训练
python3 train_lstm.py 600519 20200101 20240326 /path/to/data.csv
```

### 3. 启动推理服务

```bash
# 启动服务（预加载 600519 模型）
python3 predict_server.py --stock 600519 --port 5000

# 或先启动服务，再通过 API 加载模型
python3 predict_server.py --port 5000

# 调用 API 加载模型
curl -X POST http://localhost:5000/load/600519
```

### 4. API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/load/<stock_code>` | POST | 加载指定股票的 LSTM 模型 |
| `/predict` | POST | 预测股票走势 |
| `/train` | POST | 触发在线训练 |

#### 预测接口示例

```bash
curl -X POST http://localhost:5000/predict \
  -H "Content-Type: application/json" \
  -d '{
    "prices": [
      [50.0, 51.0, 49.5, 50.5, 1000000, 50.2, 49.8, 49.5, 55.0],
      ...（共60条历史数据，每条9个特征）
    ]
  }'
```

**响应：**
```json
{
  "trend": "UP",
  "probability": 0.72,
  "confidence": 78.5,
  "recommendation": "BUY"
}
```

## 模型说明

### 输入特征（9维）

| 索引 | 特征 | 说明 |
|------|------|------|
| 0 | open | 开盘价 |
| 1 | high | 最高价 |
| 2 | low | 最低价 |
| 3 | close | 收盘价 |
| 4 | volume | 成交量 |
| 5 | ma5 | 5日均线 |
| 6 | ma10 | 10日均线 |
| 7 | ma20 | 20日均线 |
| 8 | rsi | RSI(14) |

### 模型结构

```
Input (seq_len=60, features=9)
    ↓
LSTM (hidden=64, layers=2, dropout=0.2)
    ↓
Dropout (0.2)
    ↓
FC (64 → 32) → ReLU → Dropout
    ↓
FC (32 → 1) → Sigmoid
    ↓
Output: 涨的概率 (0~1)
```

### 预测逻辑

| 概率 | 趋势 | 信号 |
|------|------|------|
| ≥ 0.6 | UP | BUY（买入）|
| ≤ 0.4 | DOWN | SELL（卖出）|
| 0.4~0.6 | STABLE | HOLD（持有）|

置信度 = 50 + |probability - 0.5| × 100（上限100）

## Java 对接

`MLPredictionService` 已改造为优先调用 Flask HTTP API：

```
Java (Spring Boot)
    ↓ RestTemplate POST /predict
Flask Server (port 5000)
    ↓
PyTorch LSTM Model
    ↓ JSON response
Java parse → StockRecommend
```

**配置开关**（`MLPredictionService.java`）：
```java
private static final String FLASK_BASE_URL = "http://localhost:5000";
private static final boolean USE_LSTM = true;  // 设为 false 强制用 MA 策略
```

**启动顺序要求**：
1. 先启动 Flask：`python3 predict_server.py --stock 600519`
2. 再启动 Spring Boot：`mvn spring-boot:run`
3. 若 Flask 不可用，Java 自动回退到 MA 策略（无需停机）
