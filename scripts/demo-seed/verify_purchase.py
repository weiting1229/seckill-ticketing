# -*- coding: utf-8 -*-
"""端到端驗證:註冊 → 領 token → 搶購 → 輪詢結果 → 對帳。

「票種建好了」與「真的買得到」是兩件事。ONLINE 但沒 warmup 會回 3008、
沒 ONLINE 會回 3003 —— 兩者在前台都是「有票但按下去失敗」,而錯誤訊息跟
「賣完了」長得不一樣,不實際跑一次不會知道。

搶購成功會扣掉 1 張庫存並產生 1 筆訂單 —— 那是**合法**的異動,
DB / Redis / 訂單 / stock_logs 四方仍然一致(最後的 reconcile 就是在驗這件事)。
"""
import calendar
import json
import time

import _common
from _common import announce, admin_token


def call(method, path, token=None, body=None):
    # 這支要檢查 4xx 的 code(領 token / 搶購失敗時印出原因),所以錯誤回應也要拿到內容
    return _common.call(method, path, token, body, raw_error=True)


def utc_ms(iso: str) -> float:
    # API 回的是 UTC(Instant)。time.mktime 會當成本地時間解讀,在 UTC+8 會差 8 小時
    return calendar.timegm(time.strptime(iso[:19], "%Y-%m-%dT%H:%M:%S")) * 1000


def main():
    announce("端到端搶購驗證(會產生一筆真實訂單)")
    admin = admin_token()

    # 找一個「開賣中」的票種
    evs = call("GET", "/events?page=1&size=100")["data"]["items"]
    target_tt, target_ev = None, None
    for ev in evs:
        detail = call("GET", f"/events/{ev['id']}")["data"]
        now_ms = time.time() * 1000
        for t in detail["ticketTypes"]:
            start = utc_ms(t["seckillStart"])
            end = utc_ms(t["seckillEnd"])
            if start < now_ms < end and t["status"] == "ONLINE":
                target_tt, target_ev = t, detail
                break
        if target_tt:
            break
    if not target_tt:
        print("找不到開賣中的票種")
        return

    print(f"標的:{target_ev['title']} / {target_tt['name']} "
          f"價 {target_tt['price']} 總量 {target_tt['totalStock']} 前台剩餘 {target_tt['remaining']}")

    # 搶購一律需要 ROLE_USER(admin 打會 403,這是刻意的防舞弊設計)
    uname = f"e2e_{int(time.time())}"
    call("POST", "/auth/register", body={"username": uname, "password": "Password123"})
    user = call("POST", "/auth/login", body={"username": uname, "password": "Password123"})["data"]["accessToken"]

    tok = call("POST", "/seckill/token", user, {"ticketTypeId": target_tt["id"]})
    if tok.get("code") != 0:
        print("領 token 失敗:", tok)
        return
    buy = call("POST", "/seckill/purchase", user,
               {"ticketTypeId": target_tt["id"], "token": tok["data"]["token"]})
    if buy.get("code") != 0:
        print("搶購失敗:", buy)
        return
    request_id = buy["data"]["requestId"]

    result = None
    for _ in range(30):
        time.sleep(0.4)
        r = call("GET", f"/seckill/result/{request_id}", user)
        if r.get("code") == 0 and r["data"].get("status") in ("SUCCESS", "FAIL"):
            result = r["data"]
            break
    print("搶購結果:", json.dumps(result, ensure_ascii=False))

    if result and result.get("status") == "SUCCESS":
        order = call("GET", f"/orders/{result['orderId']}", user)["data"]
        print("訂單:", order["id"], order["status"], order.get("amount") or order.get("price"))

    rec = call("GET", f"/admin/ticket-types/{target_tt['id']}/reconcile", admin)
    print("對帳:", json.dumps(rec.get("data"), ensure_ascii=False))


if __name__ == "__main__":
    main()
