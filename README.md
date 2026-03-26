# A股智能选股系统

基于技术分析与机器学习的每日股票推荐系统

## 技术栈

- **后端**: Java Spring Boot
- **前端**: Vue 3
- **数据**: AKShare（免费股票数据）
- **数据库**: H2（嵌入式）
- **预测模型**: 技术指标 + LSTM机器学习

## 项目结构

```
stock-predict/
├── backend/                    # Java Spring Boot 后端
│   ├── src/main/java/com/stock/
│   │   ├── model/              # 数据模型
│   │   ├── repository/         # 数据访问层
│   │   ├── service/            # 业务逻辑
│   │   ├── controller/         # REST API
│   │   └── config/             # 配置类
│   └── pom.xml
├── frontend/                   # Vue 前端
│   └── index.html             # 直接用浏览器打开即可
└── README.md
```

## 快速启动

### 1. 安装Python依赖（数据获取用）

```bash
pip install akshare pandas numpy
```

### 2. 启动后端

```bash
cd stock-predict/backend
./mvnw spring-boot:run
```

或者打包后运行:
```bash
./mvnw package
java -jar target/stock-predict-1.0.0.jar
```

后端启动后运行在 http://localhost:8080

### 3. 打开前端

直接用浏览器打开 `frontend/index.html`

## 功能特性

### 1. 技术分析选股
- MA均线系统（MA5/MA10/MA20/MA60）
- MACD指标
- RSI相对强弱指标
- 金叉死叉识别
- 放量上涨识别

### 2. 机器学习预测
- LSTM神经网络（预留接口）
- 趋势预测
- 置信度评分

### 3. 综合推荐
- 技术分析 + 机器学习双重验证
- 买入/卖出/持有信号
- 目标价和止损价建议
- 置信度排序

### 4. 定时任务
- 每日早上9:15自动更新数据并推荐
- 可配置股票池

## API接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/stock/recommend/today` | GET | 获取今日推荐 |
| `/api/stock/recommend/buy` | GET | 获取买入信号 |
| `/api/stock/analyze/{code}` | POST | 分析指定股票 |
| `/api/stock/fetch/{code}` | POST | 获取股票数据 |

## 配置说明

### 修改默认股票池

编辑 `backend/src/main/java/com/stock/config/DailyTask.java`:

```java
private static final String[] DEFAULT_STOCKS = {
    "600519", // 贵州茅台
    "000858", // 五粮液
    "601318", // 中国平安
    // 添加更多股票...
};
```

### 修改定时任务时间

编辑 `DailyTask.java`:

```java
@Scheduled(cron = "0 15 9 * * *")  // 每天9:15执行
```

## 注意事项

1. 本系统仅供学习研究，不构成投资建议
2. 股票投资有风险，决策需谨慎
3. AKShare免费数据有频率限制
4. 机器学习模型需根据实际数据训练

## License

MIT
