#!/bin/bash
#
# 批量拉取A股历史数据（5年）
# 用法: bash batch_fetch_5y.sh [股票代码1 代码代码2 ...]
# 不传参数则使用默认股票池
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FETCH_SCRIPT="/tmp/fetch_stock.py"
DATA_DIR="/tmp/stock_data_5y"
START="2021-01-01"
END="2026-03-26"
DELAY=3   # 每次间隔秒（避免频率限制）

mkdir -p "$DATA_DIR"

# 默认股票池（可自行增减）
DEFAULT_STOCKS=(
    "000001"  # 上证指数
    "399001"  # 深证成指
    "399006"  # 创业板指
    "000300"  # 沪深300
    "000016"  # 上证50
    "000688"  # 科创50
    "600519"  # 贵州茅台
    "000858"  # 五粮液
    "601318"  # 中国平安
    "002594"  # 比亚迪
    "300059"  # 东方财富
    "300377"  # 赢时胜
    "002415"  # 海康威视
    "600111"  # 北方稀土
    "601985"  # 中国核电
    "600570"  # 恒生电子
    "000818"  # 航锦科技
)

# 参数解析
if [ $# -gt 0 ]; then
    STOCKS=("$@")
    echo "使用自定义股票池: ${STOCKS[*]}"
else
    STOCKS=("${DEFAULT_STOCKS[@]}")
    echo "使用默认股票池: ${STOCKS[*]}"
fi

TOTAL=${#STOCKS[@]}
CURRENT=0
SUCCESS=0
FAILED=0

echo "=========================================="
echo "  A股5年历史数据批量拉取"
echo "  股票数量: $TOTAL"
echo "  时间范围: $START ~ $END"
echo "  存放目录: $DATA_DIR"
echo "=========================================="

for CODE in "${STOCKS[@]}"; do
    CURRENT=$((CURRENT + 1))
    echo -n "[$CURRENT/$TOTAL] $CODE ... "

    # 指数需要传市场参数(sh/sz)
    if [[ "$CODE" =~ ^(000001|000300|000016|000688)$ ]]; then
        MARKET="sh"
    elif [[ "$CODE" =~ ^(399001|399006)$ ]]; then
        MARKET="sz"
    else
        MARKET=""
    fi

    OUTFILE="$DATA_DIR/${CODE}.json"

    # 调用fetch脚本
    if [ -z "$MARKET" ]; then
        RESULT=$(python3 "$FETCH_SCRIPT" "$CODE" "$START" "$END" 2>/dev/null)
    else
        RESULT=$(python3 "$FETCH_SCRIPT" "$CODE" "$MARKET" "$START" "$END" 2>/dev/null)
    fi

    COUNT=$(echo "$RESULT" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(len(d))" 2>/dev/null || echo "0")

    if [ "$COUNT" -gt 0 ]; then
        echo "$RESULT" > "$OUTFILE"
        echo "✅ $COUNT 条 -> $OUTFILE"
        SUCCESS=$((SUCCESS + 1))
    else
        echo "❌ 拉取失败"
        FAILED=$((FAILED + 1))
    fi

    # 最后一只不需要等待
    if [ $CURRENT -lt $TOTAL ]; then
        sleep $DELAY
    fi
done

echo ""
echo "=========================================="
echo "  完成！成功: $SUCCESS/$TOTAL  失败: $FAILED/$TOTAL"
echo "  数据目录: $DATA_DIR"
echo "=========================================="
