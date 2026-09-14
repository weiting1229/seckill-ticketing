# -*- coding: utf-8 -*-
"""demo-seed 腳本共用的 HTTP 客戶端與環境防呆。

## 憑證一律走環境變數

CLAUDE.md 的安全規範:任何祕密禁止硬編碼、禁止寫進任何會 commit 的檔案。
所以這裡沒有任何預設帳密,缺了就直接失敗。

    SECKILL_ADMIN_TOKEN=<access token>
    # 或(access token 預設 15 分鐘就過期,長流程用帳密比較不會卡在半路)
    SECKILL_ADMIN_USERNAME=... SECKILL_ADMIN_PASSWORD=...

## 為什麼打非 localhost 要額外確認

HANDOFF.md 記著一條使用者自己提的風險:PowerShell 的 `$env:` 設定會留在同一個
視窗裡,不會隨指令結束清除,所以「忘記把 BASE_URL 切回 dev 卻以為在打本機」是
實際會發生的事。這些腳本會**寫入**資料(建活動、建票種、下訂單),打錯環境的
代價比壓測更高,因此非 localhost 一律要求再設一個 SECKILL_CONFIRM_PROD=yes,
而且每次執行都把目標印出來。
"""
import json
import os
import sys
import urllib.error
import urllib.request

# Windows 主控台預設 cp950,印到非 Big5 範圍的字元(✗ ▶ 之類)會直接
# UnicodeEncodeError —— 而崩的是「印訊息」這一步,不是業務邏輯,
# 症狀會變成「腳本沒做事就掛了」而看不出原因。統一轉 UTF-8 一次解決。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass

DEFAULT_BASE = "http://localhost:8080"


def base_url() -> str:
    raw = os.environ.get("SECKILL_BASE_URL", DEFAULT_BASE).rstrip("/")
    if not raw.startswith(("http://", "https://")):
        raw = "https://" + raw
    host = raw.split("//", 1)[1].split("/", 1)[0].split(":", 1)[0]
    if host not in ("localhost", "127.0.0.1"):
        if os.environ.get("SECKILL_CONFIRM_PROD") != "yes":
            sys.exit(
                f"\n✗ 目標是 {raw}(非本機)。這支腳本會寫入資料。\n"
                f"  確定的話再設 SECKILL_CONFIRM_PROD=yes 重跑。\n"
            )
    return raw + "/api/v1"


def call(method: str, path: str, token: str | None = None, body=None, raw_error: bool = False):
    """發一個請求並回傳解析後的 ApiResponse。raw_error=True 時 4xx/5xx 也回內容而不拋。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base_url() + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if raw_error:
            return json.loads(e.read().decode("utf-8"))
        raise


def admin_token() -> str:
    token = os.environ.get("SECKILL_ADMIN_TOKEN", "").strip()
    if token:
        return token
    username = os.environ.get("SECKILL_ADMIN_USERNAME")
    password = os.environ.get("SECKILL_ADMIN_PASSWORD")
    if not username or not password:
        sys.exit(
            "\n✗ 沒有憑證。設 SECKILL_ADMIN_TOKEN,"
            "或設 SECKILL_ADMIN_USERNAME + SECKILL_ADMIN_PASSWORD。\n"
        )
    res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["accessToken"]


def announce(what: str) -> None:
    """每次執行都印出目標環境 —— 不印的話「打錯環境」只有事後從資料才看得出來。"""
    sys.stdout.write(f"\n▶ {what}\n  目標:{base_url()}\n\n")
