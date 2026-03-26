# A股智能选股系统

基于技术分析与机器学习的每日股票推荐系统

## 技术栈

- **后端**: Java Spring Boot 3.2
- **前端**: Vue 3（集成于Spring Boot静态资源）
- **数据**: AKShare（免费股票数据接口）
- **数据库**: H2（嵌入式文件数据库）
- **预测模型**: 技术指标 + LSTM机器学习

## 快速启动

### 1. 安装Python依赖

```bash
pip install akshare pandas numpy
```

### 2. 启动后端

```bash
cd stock-predict/backend
JAVA_HOME=/tmp/jdk-17 mvn spring-boot:run
```

后端运行在 **http://localhost:8080**（前端+API一体化）

### 3. 打开前端

直接访问 http://localhost:8080

## 功能特性

### 1. 技术分析选股
- MA均线系统（MA5/MA10/MA20/MA60）
- MACD指标（DIF/DEA金叉死叉）
- RSI相对强弱指标（超买超卖）
- 布林带通道
- 金叉死叉识别 + 放量上涨识别

### 2. 高级技术分析
- SAR抛物转向指标
- KDJ随机指标
- OBV能量潮
- 斐波那契回撤位
- 底背离/顶背离检测

### 3. 机器学习预测
- LSTM神经网络（预留Flask接口）
- 综合技术分析回退策略
- 置信度评分（0-100%）

### 4. K线图表
- 蜡烛图（K线）实时渲染
- MA5/MA10/MA20/MA60均线叠加
- Canvas高性能绘图

## API接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/stock/recommend/today` | GET | 获取今日推荐 |
| `/api/stock/recommend/buy` | GET | 获取买入信号 |
| `/api/stock/analyze/{code}` | POST | 综合技术分析 |
| `/api/stock/analyze/advanced/{code}` | POST | 高级技术分析 |
| `/api/stock/fetch/{code}` | POST | 拉取单只股票数据 |
| `/api/chart/kline/{code}?days=60` | GET | K线图表数据 |
| `/api/admin/bulk-import?dirPath=/path` | POST | 批量导入历史数据 |

### API文档（Swagger）
- http://localhost:8080/swagger-ui.html

## 数据获取脚本

```bash
# 单只股票拉取
python3 backend/src/main/resources/scripts/fetch_stock.py 600519 2026-01-01 2026-03-26

# 批量拉取默认17只股票（5年数据）
bash backend/src/main/resources/scripts/batch_fetch_5y.sh

# 全量A股后台拉取（约5000只，2年数据）
nohup python3 backend/src/main/resources/scripts/fetch_all_a_real.py 2>&1 &
```

## 项目结构

```
stock-predict/
├── backend/
│   ├── src/main/java/com/stock/
│   │   ├── StockPredictApplication.java
│   │   ├── config/
│   │   │   ├── DailyTask.java          # 每日定时任务
│   │   │   ├── GlobalExceptionHandler.java
│   │   │   └── OpenApiConfig.java
│   │   ├── controller/
│   │   │   ├── StockController.java
│   │   │   ├── BulkImportController.java
│   │   │   └── ChartDataController.java
│   │   ├── service/
│   │   │   ├── DataCollectionService.java
│   │   │   ├── TechnicalAnalysisService.java
│   │   │   ├── MLPredictionService.java
│   │   │   └── StockRecommendService.java
│   │   ├── model/
│   │   │   ├── StockDaily.java
│   │   │   └── StockRecommend.java
│   │   └── repository/
│   ├── src/main/resources/
│   │   ├── static/index.html          # Vue前端（已集成）
│   │   ├── scripts/
│   │   │   ├── fetch_stock.py
│   │   │   ├── batch_fetch_5y.sh
│   │   │   └── fetch_all_a_real.py
│   │   └── application.properties
│   └── pom.xml
├── task.md                             # 开发决策记录
└── TASKS.md                            # 任务进度跟踪
```

## 数据库

- **路径**: `/home/jed/stock-predict/db.mv.db`
- **配置**: `jdbc:h2:file:/home/jed/stock-predict/db`
- **H2 Console**: http://localhost:8080/h2-console

## 配置说明

### 修改默认股票池
编辑 `backend/src/main/java/com/stock/config/DailyTask.java`

### 修改定时任务时间
```java
@Scheduled(cron = "0 15 9 * * *")  // 每天9:15执行
```

## Git工作流

```bash
# 查看状态
git status

# 提交到master
git add .
git commit -m "feat: description"
git push origin master

# 合并到main
git checkout main
git merge master
git push origin main
```

## 注意事项

1. 本系统仅供学习研究，不构成投资建议
2. 股票投资有风险，决策需谨慎
3. AKShare免费数据有频率限制，请勿高频调用

## License

MIT
