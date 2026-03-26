# task.md - A股智能选股系统 开发任务

> 本文档记录项目开发任务进度与决策，供后续维护参考。

## 项目信息
- **路径**: `/home/jed/stock-predict`
- **技术栈**: Java Spring Boot 3.2 + Vue 3 + AKShare + H2 Database
- **Git**: https://github.com/Jed5/stockpredict
- **分支策略**: master（稳定版）← main（主分支）

## 一、项目结构

```
stock-predict/
├── backend/
│   ├── src/main/java/com/stock/
│   │   ├── StockPredictApplication.java    # Spring Boot入口
│   │   ├── config/
│   │   │   ├── DailyTask.java              # 每日定时任务
│   │   │   ├── GlobalExceptionHandler.java  # 全局异常处理
│   │   │   └── OpenApiConfig.java          # Swagger API文档配置
│   │   ├── controller/
│   │   │   ├── StockController.java         # 股票分析REST API
│   │   │   ├── BulkImportController.java   # 批量数据导入API
│   │   │   └── ChartDataController.java    # K线图表数据API
│   │   ├── service/
│   │   │   ├── DataCollectionService.java   # AKShare数据采集
│   │   │   ├── TechnicalAnalysisService.java # 技术指标分析
│   │   │   ├── MLPredictionService.java     # 机器学习预测（含高级分析）
│   │   │   ├── StockRecommendService.java   # 推荐服务
│   │   │   └── StockInfoService.java       # 股票信息
│   │   ├── model/
│   │   │   ├── StockDaily.java             # 日线数据实体
│   │   │   └── StockRecommend.java          # 推荐结果实体
│   │   └── repository/
│   │       ├── StockDailyRepository.java
│   │       └── StockRecommendRepository.java
│   ├── src/main/resources/
│   │   ├── static/index.html               # Vue前端页面
│   │   ├── scripts/
│   │   │   ├── fetch_stock.py              # 单股票数据拉取
│   │   │   ├── batch_fetch_5y.sh           # 批量拉取5年数据脚本
│   │   │   └── fetch_all_a_real.py         # 全量A股后台拉取脚本
│   │   └── application.properties          # Spring Boot配置
│   └── pom.xml
├── docs/                                   # 项目文档
├── README.md
├── TASKS.md                                # 任务清单（进度跟踪）
└── task.md                                 # 本文件（开发决策记录）
```

## 二、API接口

### 核心接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/stock/analyze/{code}` | 综合技术分析（MA/MACD/RSI/布林带） |
| POST | `/api/stock/analyze/advanced/{code}` | 高级分析（SAR/KDJ/OBV/斐波那契/背离） |
| POST | `/api/stock/fetch/{code}` | 从AKShare拉取单只股票数据 |
| POST | `/api/stock/fetch/advanced/{code}` | 高级技术分析+实时数据 |
| GET | `/api/stock/recommend/today` | 今日推荐列表 |
| GET | `/api/chart/kline/{code}?days=60` | K线图表数据（含MA5/10/20/60） |
| POST | `/api/admin/bulk-import?dirPath=/path` | 批量导入JSON历史数据 |

### API文档
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## 三、数据模型

### StockDaily（日线数据）
```
stock_code   VARCHAR(20)    股票代码（如 600519）
stock_name  VARCHAR(50)    股票名称（如 贵州茅台）
trade_date  DATE           交易日期
open        DECIMAL        开盘价
high        DECIMAL        最高价
low         DECIMAL        最低价
close       DECIMAL        收盘价
volume      DECIMAL        成交量
amount      DECIMAL        成交额
```

### StockRecommend（推荐结果）
```
stock_code       VARCHAR(20)   股票代码
stock_name       VARCHAR(50)   股票名称
recommend_date   DATE          推荐日期
signal_type      VARCHAR(10)   信号类型（BUY/SELL/HOLD）
signal_source    VARCHAR(20)  信号来源（TECHNICAL/ML/BOTH）
confidence_score DOUBLE        置信度 0-100
target_price     DECIMAL       目标价
stop_loss        DECIMAL       止损价
reason           TEXT          推荐理由
```

## 四、核心算法

### 技术分析指标
1. **均线系统**: MA5/MA10/MA20/MA60
2. **MACD**: DIF/DEA柱状图，金叉死叉
3. **RSI**: 相对强弱指数（超买超卖）
4. **布林带**: 中轨+上下轨通道
5. **成交量分析**: 量价配合

### 高级分析指标
1. **SAR**: 抛物转向指标
2. **KDJ**: 随机指标（超买超卖）
3. **OBV**: 能量潮指标
4. **斐波那契回撤**: 关键支撑阻力位
5. **背离检测**: 底背离/顶背离确认

### 评分规则（综合分析）
```
+3.0 金叉 BUY
-3.0 死叉 SELL
-2.0 均线空头排列
+2.0 均线多头排列
+2.0 MACD水上看涨
-2.0 MACD水下看跌
+2.0 RSI超卖（<30）
-2.0 RSI超买（>70）
-1.0 DIF下降
+1.0 DIF上升
+0.5 布林带下轨附近
+0.5 成交量放大
```

### 高级分析评分
```
>=+5 BUY
<=-5 SELL
中间 HOLD
```

## 五、关键决策记录

### 2026-03-26 数据导入策略
- **问题**: 一次性导入5000+股票数据导致JPA EntityManager超时
- **解决**: 改用批量导入接口，每次后台导入1000只，分批进行
- **教训**: 大批量INSERT需要加事务控制或分批提交

### 2026-03-26 数据库持久化确认
- H2使用文件模式 `jdbc:h2:file:/home/jed/stock-predict/db`
- 数据写入 `db.mv.db` 文件，进程重启不丢失
- 注意: 旧的错误数据（600570 ¥123.27）通过重建数据库解决

### 2026-03-26 前端图表数据
- 原版前端使用模拟数据生成图表
- 改进: 新增 `/api/chart/kline/{code}` 接口提供真实数据
- 图表支持K线（蜡烛图）和MA均线叠加显示

### 2026-03-26 AKShare API限制
- eastmoney接口存在限速，每次需间隔3秒以上
- 部分股票因停牌/退市无法获取数据，属正常
- `stock_info_a_code_name()` 可获取真实A股代码（5493只）

## 六、Git工作流

### 分支策略
```
master   ← 稳定版本（已验证）
main     ← 主分支（最新代码）
```

### 提交规范
```bash
feat:     新功能
fix:      Bug修复
docs:     文档更新
refactor: 重构
test:     测试相关
chore:    构建/工具
```

### 合并命令
```bash
git checkout main
git merge master
git push origin main
```

## 七、部署

### 构建
```bash
cd backend
JAVA_HOME=/tmp/jdk-17 mvn clean package -DskipTests
```

### 运行
```bash
java -jar target/stock-predict-1.0.0.jar
# 或
mvn spring-boot:run
```

### 前端
- 已集成到Spring Boot静态资源
- 访问 http://localhost:8080

## 八、待优化项

- [ ] LSTM模型实际加载（Flask服务）
- [ ] 邮件/推送通知
- [ ] 股票池自动化配置
- [ ] Docker容器化部署
- [ ] 历史回测框架
