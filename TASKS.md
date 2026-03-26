# A股智能选股系统 - 任务列表

## 项目信息
- 路径: ~/stock-predict
- 技术栈: Java Spring Boot + Vue + AKShare
- 模型: Minimax-M2.7

## 待完成任务

### 1. 项目初始化 ✅ 已完成
- [x] Maven 项目结构
- [x] pom.xml 依赖配置
- [x] Spring Boot 启动类

### 2. 数据模型层 ✅ 已完成
- [x] StockDaily 股票日线数据实体
- [x] StockRecommend 推荐结果实体
- [x] Repository 数据访问层

### 3. 服务层 ⚙️ 进行中
- [x] DataCollectionService 数据采集
- [x] TechnicalAnalysisService 技术分析
- [x] MLPredictionService 机器学习预测
- [ ] StockRecommendService 推荐服务完善
- [ ] 集成测试

### 4. API 层 ⚙️ 进行中
- [x] StockController REST接口
- [ ] API文档
- [ ] 错误处理完善

### 5. 前端 ⚙️ 进行中
- [x] index.html 页面框架
- [ ] 图表展示（K线图）
- [ ] 股票搜索功能

### 6. 定时任务 ⚙️ 进行中
- [x] DailyTask 每日定时任务
- [ ] 股票池配置
- [ ] 邮件/推送通知

### 7. 测试验证 📋 待开始
- [ ] 单元测试
- [ ] 集成测试
- [ ] API 测试

### 8. 部署上线 📋 待开始
- [ ] 打包脚本
- [ ] 部署文档
- [ ] 运维手册

## 当前优先级
1. 完成服务层剩余功能
2. 完善前端图表
3. 编写测试用例
4. 验证整体流程
