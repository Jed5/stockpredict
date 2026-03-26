"""
Flask HTTP 推理服务
用法: python3 predict_server.py [stock_code]
示例: python3 predict_server.py 600519

POST /predict
Body: {"prices": [[open, high, low, close, volume, ma5, ma10, ma20, rsi], ...]}

响应:
{
    "trend": "UP" | "DOWN" | "STABLE",
    "confidence": 0-100,
    "probability": 0.0-1.0,
    "recommendation": "BUY" | "SELL" | "HOLD"
}
"""

import sys
import os
import numpy as np
import pickle
import torch
from flask import Flask, request, jsonify
import warnings

warnings.filterwarnings("ignore")

from lstm_model import LSTMModel
from feature_engineering import extract_features

app = Flask(__name__)

# ============ 配置 ============
MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")
SEQ_LEN = 60
HIDDEN_SIZE = 64
NUM_LAYERS = 2
DROPOUT = 0.2

# 全局模型和标准化器
model = None
scaler = None
current_stock_code = None

# ============ 路由 ============

@app.route("/health", methods=["GET"])
def health():
    """健康检查"""
    return jsonify({
        "status": "ok",
        "model_loaded": model is not None,
        "stock_code": current_stock_code
    })


@app.route("/predict", methods=["POST"])
def predict():
    """
    预测接口
    Body: {
        "prices": [[open, high, low, close, volume, ma5, ma10, ma20, rsi], ...],
        "seq_len": 60  (可选，默认60)
    }
    """
    if model is None:
        return jsonify({"error": "模型未加载，请先调用 /load/{stock_code}"}), 400

    try:
        data = request.json
        prices = data.get("prices", [])

        if len(prices) < SEQ_LEN:
            return jsonify({
                "error": f"数据不足，需要至少 {SEQ_LEN} 条历史数据，当前 {len(prices)} 条"
            }), 400

        # 取最后 SEQ_LEN 条作为输入
        input_seq = np.array(prices[-SEQ_LEN:])

        # 标准化
        input_scaled = scaler.transform(input_seq)

        # 转为 PyTorch 张量 (1, seq_len, features)
        X = torch.FloatTensor(input_scaled).unsqueeze(0)

        # 推理
        model.eval()
        with torch.no_grad():
            prob = model(X).item()

        # 趋势判断
        if prob >= 0.6:
            trend = "UP"
            recommendation = "BUY"
            confidence = round(min(100, 50 + (prob - 0.5) * 100), 2)
        elif prob <= 0.4:
            trend = "DOWN"
            recommendation = "SELL"
            confidence = round(min(100, 50 + (0.5 - prob) * 100), 2)
        else:
            trend = "STABLE"
            recommendation = "HOLD"
            confidence = round(50 + abs(prob - 0.5) * 50, 2)

        return jsonify({
            "trend": trend,
            "probability": round(prob, 4),
            "confidence": confidence,
            "recommendation": recommendation,
            "model": current_stock_code
        })

    except Exception as e:
        import traceback
        return jsonify({"error": str(e), "trace": traceback.format_exc()}), 500


@app.route("/load/<stock_code>", methods=["POST"])
def load_model(stock_code):
    """加载指定股票的模型"""
    global model, scaler, current_stock_code

    model_path = os.path.join(MODEL_DIR, f"{stock_code}_lstm.pth")
    scaler_path = os.path.join(MODEL_DIR, f"{stock_code}_scaler.pkl")

    if not os.path.exists(model_path):
        return jsonify({
            "error": f"模型文件不存在: {model_path}",
            "hint": f"请先运行: python3 train_lstm.py {stock_code}"
        }), 404

    if not os.path.exists(scaler_path):
        return jsonify({"error": f"标准化器不存在: {scaler_path}"}), 404

    try:
        # 加载标准化器
        with open(scaler_path, "rb") as f:
            scaler = pickle.load(f)

        # 加载模型
        model = LSTMModel(input_size=9, hidden_size=HIDDEN_SIZE,
                          num_layers=NUM_LAYERS, dropout=DROPOUT)
        model.load_state_dict(torch.load(model_path, map_location="cpu"))
        model.eval()

        current_stock_code = stock_code
        print(f"[模型加载] {stock_code}_lstm.pth")

        return jsonify({
            "status": "loaded",
            "stock_code": stock_code,
            "model_path": model_path
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/train", methods=["POST"])
def train_api():
    """
    触发在线训练接口（简单版本）
    Body: {
        "stock_code": "600519",
        "start_date": "20200101",
        "end_date": "20240326"
    }
    """
    import subprocess

    data = request.json
    stock_code = data.get("stock_code", "600519")
    start_date = data.get("start_date", "20200101")
    end_date = data.get("end_date", "20240326")

    try:
        result = subprocess.run(
            ["python3", os.path.join(os.path.dirname(__file__), "train_lstm.py"),
             stock_code, start_date, end_date],
            capture_output=True, text=True, timeout=600
        )

        if result.returncode == 0:
            # 训练成功后自动加载
            return load_model(stock_code)
        else:
            return jsonify({
                "error": "训练失败",
                "stdout": result.stdout,
                "stderr": result.stderr
            }), 500

    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="LSTM 股票预测推理服务")
    parser.add_argument("--stock", type=str, default=None,
                        help="预加载的股票代码，如 600519")
    parser.add_argument("--port", type=int, default=5000, help="服务端口")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="服务地址")

    args = parser.parse_args()

    # 如果指定了股票代码，先加载模型
    if args.stock:
        model_path = os.path.join(MODEL_DIR, f"{args.stock}_lstm.pth")
        scaler_path = os.path.join(MODEL_DIR, f"{args.stock}_scaler.pkl")

        if os.path.exists(model_path) and os.path.exists(scaler_path):
            with open(scaler_path, "rb") as f:
                scaler = pickle.load(f)
            model = LSTMModel(input_size=9, hidden_size=HIDDEN_SIZE,
                              num_layers=NUM_LAYERS, dropout=DROPOUT)
            model.load_state_dict(torch.load(model_path, map_location="cpu"))
            model.eval()
            current_stock_code = args.stock
            print(f"[启动] 已加载模型: {args.stock}")
        else:
            print(f"[警告] 模型文件不存在: {model_path}")

    print(f"[启动] 推理服务运行在 http://{args.host}:{args.port}")
    app.run(host=args.host, port=args.port, debug=False)
