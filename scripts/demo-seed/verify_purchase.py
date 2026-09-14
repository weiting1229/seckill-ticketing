# -*- coding: utf-8 -*-
"""端到端驗證:註冊 → 領 token → 搶購 → 輪詢結果 → 對帳。

「票種建好了」與「真的買得到」是兩件事。ONLINE 但沒 warmup 會回 3008、
沒 ONLINE 會回 3003 —— 兩者在前台都是「有票但按下去失敗」,而錯誤訊息跟
「賣完了」長得不一樣,不實際跑一次不會知道。

搶購成功會扣掉 1 張庫存並產生 1 筆訂單 —— 那是**合法**的異動,
DB / Redis / 訂單 / stock_logs 四方仍然一致(最後的 reconcile 就是在驗這件事)。
"""
import json
import time

from _common import announce, admin_token, base_url, call



def call(method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))


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
            start = time.mktime(time.strptime(t["seckillStart"][:19], "%Y-%m-%dT%H:%M:%S")) * 1000
            end = time.mktime(time.strptime(t["seckillEnd"][:19], "%Y-%m-%dT%H:%M:%S")) * 1000
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
