# A股智能选股系统 — 项目文档

> 本文档详细说明系统的设计原理、预测方法、API 使用和运行指南。

---

## 一、系统概述

本系统是一款基于 **技术分析 + 机器学习** 的 A 股智能选股推荐工具，每日自动分析股票走势，给出买入/卖出信号及置信度评分。

### 技术栈

| 层次 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2 + Java 17 |
| 数据库 | H2（内存/文件，SQLite 风格） |
| 数据获取 | Python AKShare 库 |
| 前端 | Vue 3 + Axios（单页应用） |
| 构建工具 | Maven |

### 核心文件结构

```
stock-predict/
├── backend/
│   └── src/main/java/com/stock/
│       ├── StockPredictApplication.java    # 启动入口
│       ├── controller/
│       │   └── StockController.java        # REST API 控制器
│       ├── service/
│       │   ├── TechnicalAnalysisService.java  # 技术分析引擎
│       │   ├── MLPredictionService.java       # ML 预测引擎
│       │   ├── DataCollectionService.java     # 数据采集服务
│       │   ├── StockRecommendService.java     # 推荐合并逻辑
│       │   └── StockInfoService.java          # 股票信息映射
│       ├── model/
│       │   ├── StockDaily.java              # 日线行情实体
│       │   └── StockRecommend.java           # 推荐结果实体
│       ├── repository/
│       │   ├── StockDailyRepository.java
│       │   └── StockRecommendRepository.java
│       └── config/
│           └── DailyTask.java               # 每日定时任务
└── frontend/
    └── index.html                          # 前端单页应用
```

---

## 二、预测原理详解

系统采用**双引擎**架构：技术分析引擎 + 机器学习预测引擎，两者独立运行，最终合并输出。

### 2.1 技术分析引擎（TechnicalAnalysisService）

技术分析基于历史价格和成交量数据，计算多个技术指标，综合判断买卖信号。

#### 核心指标

**① 移动平均线（MA）**

| 指标 | 计算方法 | 含义 |
|------|---------|------|
| MA5 | 最近 5 日收盘价的平均值 | 短期趋势 |
| MA10 | 最近 10 日收盘价的平均值 | 中期趋势 |
| MA20 | 最近 20 日收盘价的平均值 | 中长期趋势 |
| MA60 | 最近 60 日收盘价的平均值 | 长期趋势 |

**② 金叉 / 死叉（Golden Cross / Death Cross）**

- **金叉（买入信号）**：MA5 从下方穿过 MA10 → 短期上涨趋势确认
- **死叉（卖出信号）**：MA5 从上方穿过 MA10 → 短期下跌趋势确认

> ⚠️ **已知 BUG**：当前实现中 `calculateMArray` 方法传参 `start == end`，只取了单个价格点，而非均线值，导致金叉/死叉判断逻辑失效。此 BUG 已在测试中发现，需修复。

**③ MACD（Moving Average Convergence Divergence）**

| 参数 | 值 | 含义 |
|------|-----|------|
| 快线 EMA | 12 日 EMA | 短期平滑均价 |
| 慢线 EMA | 26 日 EMA | 长期平滑均价 |
| DIF | EMA12 - EMA26 | 快慢线差值 |
| DEA | DIF 的 9 日 EMA | 信号线 |
| BAR | (DIF - DEA) × 2 | MACD 柱状图 |

**买入条件**：`DIF > DEA` 且 `DEA < 0`（零轴下方金叉，超卖反弹）

**④ RSI（Relative Strength Index）**

RSI 衡量价格涨跌的相对强度，取值 0-100：

| RSI 区间 | 信号 | 含义 |
|---------|------|------|
| < 30 | 超卖 | 价格过低，可能反弹 |
| 30–70 | 正常 | 无明显信号 |
| > 70 | 超买 | 价格过高，可能回调 |

**⑤ 成交量分析（Volume）**

- 计算最近 5 日平均成交量
- 若今日成交量 > 平均成交量 × 1.5，则为"放量"
- 放量上涨是强势信号，放量下跌是弱势信号

#### 技术分析决策逻辑

```
技术分析选股流程：

输入: 股票代码
数据: 最近 100 个交易日日线数据

1. 获取历史数据，若不足 30 天 → 返回空

2. 计算所有技术指标:
   - MA5, MA10, MA20, MA60
   - MACD (DIF, DEA, BAR)
   - RSI(14)
   - 成交量比率

3. 买入信号条件（需同时满足）:
   - goldenCross == true   （MA5 上穿 MA10）
   - macdBuy == true       （DIF > DEA 且 DEA < 0）
   - confidence > 60       （综合置信度 > 60）

4. 卖出信号条件:
   - deathCross == true    （MA5 下穿 MA10）
   - confidence > 70       （综合置信度 > 70）

5. 返回推荐列表（StockRecommend）
```

**置信度评分规则（满分 100）**：

| 信号 | 加分 |
|------|------|
| 金叉 | +20 |
| MACD 买入 | +20 |
| RSI 超卖 | +15 |
| 放量上涨 | +15 |
| 死叉 | +25 |

---

### 2.2 机器学习预测引擎（MLPredictionService）

ML 引擎使用 Python 调用内嵌的 LSTM 预测脚本（目前为简化版移动平均预测）。

#### 预测流程

```
ML 预测流程：

输入: 股票代码
数据: 最近 200 个交易日日线数据

1. 数据准备:
   - 若历史数据 < 60 天 → 返回 null（数据不足）

2. 提取特征:
   - 收盘价序列 [price_1, price_2, ..., price_n]
   - 计算 MA5 = mean(prices[-5:])
   - 计算 MA20 = mean(prices[-20:])
   - 当前价格 = prices[-1]

3. 趋势判断:
   - UP:    MA5 > MA20 AND current > MA5
   - DOWN:  MA5 < MA20 AND current < MA5
   - STABLE: 其他情况

4. 置信度计算:
   - UP/DOWN 趋势强度 = |MA5/MA20 - 1| × 1000
   - confidence = min(95, 60 + 趋势强度)

5. 预测价格:
   - UP:    predicted = current × 1.05
   - DOWN:  predicted = current × 0.95
   - STABLE: predicted = current

6. 信号映射:
   - UP → BUY（买入）
   - DOWN → SELL（卖出）
   - STABLE → HOLD（持有）

7. 返回推荐（StockRecommend）
```

> ⚠️ **说明**：当前 ML 引擎使用的是简化版移动平均预测，属于**占位实现**。生产环境应替换为真实训练好的 LSTM/Transformer 模型。

#### Java 与 Python 的交互

```
Java (MLPredictionService)
    │
    ├─ 1. 提取价格数组 double[]
    │
    ├─ 2. 构建 Python 脚本字符串（内嵌）
    │
    ├─ 3. ProcessBuilder 启动 python3 -c "<script>"
    │       └─ 子进程执行 Python 代码
    │           └─ numpy 计算 MA5/MA20
    │           └─ JSON 输出结果
    │
    ├─ 4. 读取 stdout，解析 JSON
    │
    └─ 5. 转换为 StockRecommend 对象
```

---

### 2.3 推荐合并（StockRecommendService）

两种引擎独立运行，结果合并去重：

```
推荐合并流程：

输入: 技术分析结果 List<StockRecommend> + ML预测结果 StockRecommend

1. 收集所有推荐

2. 按股票代码分组:
   groupBy(stockCode)

3. 同股票多信号时:
   - 取置信度（confidenceScore）最高的那个

4. 按置信度降序排序

5. 返回最终推荐列表
```

---

## 三、数据采集流程

### 3.1 数据来源

使用 Python [AKShare](https://github.com/akfamily/akshare) 库获取东方财富（East Money）股票数据。

AKShare 调用：
```python
ak.stock_zh_a_hist(symbol="sh600519", start_date="20240101", end_date="20240326", adjust="qfq")
```

### 3.2 采集字段

每条日线数据包含：

| 字段 | 说明 |
|------|------|
| trade_date | 交易日期 |
| open | 开盘价 |
| high | 最高价 |
| low | 最低价 |
| close | 收盘价 |
| volume | 成交量 |
| amount | 成交额 |

### 3.3 定时任务（DailyTask）

每天 **9:15**（A股开盘前）自动执行：

```
每日定时任务：

触发时间: 每天 9:15（交易日）

监控股票列表:
  - 600519 贵州茅台
  - 000858 五粮液
  - 601318 中国平安
  - 600036 招商银行
  - 000333 美的集团

执行步骤:
  for each stockCode in [列表]:
      1. fetchStockData(stockCode, -100天, 今天)
         └─ 调用 AKShare 获取历史数据，存入 H2 数据库
      2. getCombinedRecommendations(stockCode)
         └─ 技术分析 + ML 预测，生成推荐
      3. 保存推荐结果到 stock_recommend 表

依赖: akshare Python 库
```

---

## 四、API 接口

基础路径: `http://localhost:8080/api/stock`

### 4.1 获取今日推荐列表

```
GET /api/stock/recommend/today
```

**响应示例:**
```json
[
  {
    "id": 1,
    "stockCode": "600519",
    "stockName": "贵州茅台",
    "recommendDate": "2026-03-26",
    "signalType": "BUY",
    "signalSource": "TECHNICAL",
    "confidenceScore": 75.0,
    "targetPrice": 1650.0,
    "stopLoss": 1570.0,
    "reason": "技术指标金叉+MACD买入信号",
    "createdAt": "2026-03-26T09:15:00"
  }
]
```

### 4.2 获取今日买入信号

```
GET /api/stock/recommend/buy
```

### 4.3 分析指定股票

```
POST /api/stock/analyze/{stockCode}

示例: POST http://localhost:8080/api/stock/analyze/600519
```

### 4.4 获取股票数据

```
POST /api/stock/fetch/{stockCode}

示例: POST http://localhost:8080/api/stock/fetch/600519

响应:
{
  "success": true,
  "stockCode": "600519"
}
```

> ⚠️ 需要安装 akshare: `pip3 install akshare`

---

## 五、数据库

### 5.1 H2 控制台

访问地址: `http://localhost:8080/h2-console`

| 配置项 | 值 |
|--------|---|
| JDBC URL | `jdbc:h2:~/stock-predict/db` |
| 用户名 | `sa` |
| 密码 | （空） |

### 5.2 表结构

**stock_daily（股票日线行情）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| stock_code | VARCHAR(20) | 股票代码 |
| stock_name | VARCHAR(50) | 股票名称 |
| trade_date | DATE | 交易日期 |
| open | DECIMAL | 开盘价 |
| high | DECIMAL | 最高价 |
| low | DECIMAL | 最低价 |
| close | DECIMAL | 收盘价 |
| volume | DECIMAL | 成交量 |
| amount | DECIMAL | 成交额 |

**stock_recommend（推荐结果）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| stock_code | VARCHAR(20) | 股票代码 |
| stock_name | VARCHAR(50) | 股票名称 |
| recommend_date | DATE | 推荐日期 |
| signal_type | VARCHAR(20) | BUY/SELL/HOLD |
| signal_source | VARCHAR(50) | TECHNICAL/ML |
| confidence_score | DOUBLE | 置信度 0-100 |
| target_price | DOUBLE | 目标价 |
| stop_loss | DOUBLE | 止损价 |
| reason | VARCHAR(500) | 推荐理由 |
| created_at | TIMESTAMP | 创建时间 |

---

## 六、运行指南

### 6.1 环境要求

- Java 17+
- Maven 3.8+
- Python 3.8+
- akshare: `pip3 install akshare numpy`

### 6.2 启动后端

```bash
cd ~/stock-predict/backend

# 设置环境变量
export JAVA_HOME=/tmp/jdk-17
export MAVEN_HOME=/tmp/apache-maven-3.9.5
export PATH=$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH

# 编译并启动
mvn spring-boot:run

# 或后台运行
mvn spring-boot:run &
sleep 20
curl http://localhost:8080/api/stock/recommend/today
```

### 6.3 启动前端

直接用浏览器打开 `~/stock-predict/frontend/index.html` 即可（前端是纯静态文件，无需构建）。

### 6.4 运行测试

```bash
cd ~/stock-predict/backend
mvn test

# 查看详细测试报告
mvn test -Dsurefire.reportFormat=verbose
```

---

## 七、已知问题（生产环境慎用）

| # | 严重度 | 问题描述 | 建议 |
|---|--------|---------|------|
| 1 | 🔴 严重 | 金叉/死叉判断逻辑 BUG，`calculateMArray` 传 `start==end` 只取单点而非均线 | 修复 `calculateMArray` 调用逻辑 |
| 2 | 🔴 严重 | `history` 可能为 null，未做 null 检查 | 已修复 |
| 3 | 🟡 中等 | `parseSimpleJson` 用 `split(",")` 无法处理含逗号的字符串值 | 改用 Jackson ObjectMapper |
| 4 | 🟡 中等 | ProcessBuilder 无超时保护，Python 卡住会阻塞线程 | 添加 `process.waitFor(timeout)` |
| 5 | 🟢 低 | ML 引擎是简化版占位实现，非真实 LSTM 模型 | 替换为训练好的深度学习模型 |

---

## 八、扩展建议

1. **接入真实 ML 模型**：训练 LSTM/Transformer 模型预测股价，将 Python 脚本替换为真实推理接口
2. **增加风控模块**：止损/止盈策略、仓位管理
3. **增加回测模块**：用历史数据验证策略收益率、夏普比率
4. **前端增强**：加入 K 线图、资金流向、消息通知
5. **多数据源**：接入基本面数据（财报、估值）、消息面（财经新闻情感分析）
