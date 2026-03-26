#!/usr/bin/env python3
"""
全量A股历史数据后台拉取（最近N年）
用法: python3 fetch_all_a_queue.py [年数默认2]
后台运行: nohup python3 fetch_all_a_queue.py 2>&1 &
"""

import akshare as ak
import json, time, os, sys
from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading

YEARS = int(sys.argv[1]) if len(sys.argv) > 1 else 2
END = datetime.now().strftime("%Y-%m-%d")
START = (datetime.now() - timedelta(days=YEARS * 365)).strftime("%Y-%m-%d")
OUT_DIR = f"/tmp/stock_data_a_{YEARS}y"
os.makedirs(OUT_DIR, exist_ok=True)
DONE_FILE = f"{OUT_DIR}/done.json"
FAILED_FILE = f"{OUT_DIR}/failed.json"

# ===== 正确的A股代码范围 =====
def gen_range(start, end):
    return [f"{i:06d}" for i in range(start, end)]

# 沪市: 600000~604099 (主板) + 688000~688999 (科创板)
SH_CODES = sorted(set(gen_range(600000, 604100) + gen_range(688000, 689000)))
# 深市主板: 000000~004099
SZ_MAIN_CODES = sorted(set(gen_range(0, 4100)))
# 中小板: 002000~002999
SZ_SME_CODES = sorted(set(gen_range(2000, 3000)))
# 创业板: 300000~304999
CYB_CODES = sorted(set(gen_range(300000, 305000)))
# 北交所: 8开头
BJ_CODES = sorted(set(gen_range(800000, 805000)))

ALL_CODES = sorted(set(SH_CODES + SZ_MAIN_CODES + SZ_SME_CODES + CYB_CODES + BJ_CODES))
print(f"预定义A股代码: {len(ALL_CODES)} 只")
print(f"  沪市(600/688): {len(SH_CODES)}")
print(f"  深市主板(000~004): {len(SZ_MAIN_CODES)}")
print(f"  中小(002): {len(SZ_SME_CODES)}")
print(f"  创业板(300): {len(CYB_CODES)}")
print(f"  北交所(8): {len(BJ_CODES)}")

# 指数代码
INDEX_CODES = {"000001","399001","399006","000300","000016","000688"}

# ===== 进度 =====
def load_set(fname):
    if os.path.exists(fname):
        return set(json.load(open(fname)))
    return set()

done = load_set(DONE_FILE)
failed = load_set(FAILED_FILE)
queue = [c for c in ALL_CODES if c not in done and c not in failed]
total = len(queue)
total_done = len(done)
start_time = time.time()
lock = threading.Lock()
ok_count = [0]
fail_count = [0]

print(f"待拉取: {total} 只 | 已完成: {total_done} 只")

# ===== 拉取单只 =====
def fetch_one(code):
    for attempt in range(3):
        try:
            if code in INDEX_CODES:
                sym = ("sh" if code.startswith("000") else "sz") + code
                df = ak.stock_zh_index_daily(symbol=sym)
            else:
                market = "sh" if code.startswith("6") or code.startswith("5") else "sz"
                sym = market + code
                df = ak.stock_zh_a_daily(symbol=sym, adjust="qfq")

            df = df[df["date"].astype(str) >= START]
            df = df[df["date"].astype(str) <= END]

            if df.empty:
                with lock:
                    fail_count[0] += 1
                return None

            records = []
            for _, row in df.iterrows():
                records.append({
                    "date":   str(row["date"]),
                    "open":   float(row["open"]),
                    "high":   float(row["high"]),
                    "low":    float(row["low"]),
                    "close":  float(row["close"]),
                    "volume": float(row["volume"]),
                    "amount": float(row.get("amount") or 0),
                })

            with open(f"{OUT_DIR}/{code}.json", "w") as f:
                json.dump(records, f, ensure_ascii=False)

            with lock:
                done.add(code)
                save(done, DONE_FILE)
                ok_count[0] += 1
            return code

        except Exception as e:
            if attempt < 2:
                time.sleep(2)
            continue

    with lock:
        failed.add(code)
        save(failed, FAILED_FILE)
        fail_count[0] += 1
    return None

def save(data, fname):
    with open(fname, "w") as f:
        json.dump(sorted(data), f)

if total == 0:
    print("全部完成!")
    sys.exit(0)

print(f"\n开始拉取（3秒/只并行3线程，失败自动重试）...")
print("后台命令: nohup python3 fetch_all_a_queue.py 2>&1 &\n")

with ThreadPoolExecutor(max_workers=3) as pool:
    futures = {pool.submit(fetch_one, code): code for code in queue}
    for future in as_completed(futures):
        cur = ok_count[0] + fail_count[0]
        pct = int(cur / total * 100)
        bar = chr(9608) * int(cur / total * 40)
        elapsed = time.time() - start_time
        eta = (elapsed / max(1, cur) * (total - cur) / 60) if cur > 0 else 0
        print(f"\r  [{bar:<40}] {cur}/{total} ({pct}%)  成功{ok_count[0]}  失败{fail_count[0]}  {elapsed/60:.0f}min  ETA:{eta:.0f}min", end="", flush=True)

print()
print(f"\n本批完成! 成功:{ok_count[0]} 失败:{fail_count[0]}  耗时:{(time.time()-start_time)/60:.1f}min")
