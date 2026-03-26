#!/bin/bash

echo "=========================================="
echo "A股智能选股系统启动脚本"
echo "=========================================="

# 检查Python依赖
echo "[1/4] 检查Python依赖..."
python3 -c "import akshare" 2>/dev/null
if [ $? -ne 0 ]; then
    echo "正在安装 akshare..."
    pip install akshare pandas numpy -q
fi

# 启动后端
echo "[2/4] 启动后端服务..."
cd "$(dirname "$0")/backend"
if [ ! -f "mvnw" ]; then
    ./mvnw wrapper:wrapper
fi
./mvnw spring-boot:run &

echo "[3/4] 等待后端启动..."
sleep 10

echo "[4/4] 启动完成！"
echo ""
echo "后端API: http://localhost:8080"
echo "H2控制台: http://localhost:8080/h2-console"
echo "前端: 用浏览器打开 frontend/index.html"
echo ""
echo "按 Ctrl+C 停止服务"
