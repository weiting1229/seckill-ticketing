# -*- coding: utf-8 -*-
"""為 46 個藝人活動建票種,並逐一 warmup(上線 + 寫入 Redis)。

## 為什麼建完要 warmup

`POST /admin/ticket-types` 建出來的票種一律是 OFFLINE,而且 Redis 沒有庫存 key。
不 warmup 的話前台看得到票種、按下去卻是 3003(票種未上線)或 3008(尚未就緒)——
畫面上有票、買不到,而且錯誤訊息跟「賣完了」長得不像。
`POST /admin/ticket-types/{id}/warmup` 一次做完上線與預熱,且冪等。

## 庫存刻意不造假

全部票種都是滿庫存。要讓前台出現「熱賣中 / 剩餘少量」就得偽造 stockRemaining,
而那會讓 DB / Redis / 有效訂單 / stock_logs 的四方對帳(ReconcileService)當場不一致 ——
那正是這個專案花力氣在偵測的東西,不該為了畫面好看自己製造一筆。
"""
import sys

from _common import announce, admin_token, call
from datetime import datetime, timedelta, timezone


# 場地規模決定票種結構:(票種名, 基礎價, 庫存)
LARGE = ["台北小巨蛋", "高雄流行音樂中心", "新莊體育館", "桃園國際棒球場",
         "台北國際會議中心", "台南文化中心"]
LIVEHOUSE = ["Legacy Taipei", "THE WALL 公館", "Zepp New Taipei", "河岸留言西門紅樓"]

LAYOUTS = {
    "large": [("搖滾區", 3880, 800), ("看台 A 區", 2880, 1500), ("看台 B 區", 1880, 2000)],
    "livehouse": [("預售全區站票", 1280, 350), ("現場全區站票", 1480, 80)],
    "outdoor": [("草地席", 1680, 1200), ("特區座位", 2480, 400)],
}




def layout_for(venue):
    if venue in LARGE:
        return LAYOUTS["large"]
    if venue in LIVEHOUSE:
        return LAYOUTS["livehouse"]
    return LAYOUTS["outdoor"]


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    announce("為 46 個活動建票種並 warmup")
    token = admin_token()

    events, page = [], 1
    while True:
        res = call("GET", f"/admin/events?page={page}&size=100", token)["data"]
        events.extend(res["items"])
        if page * res["size"] >= res["total"]:
            break
        page += 1
    events.sort(key=lambda e: e["title"])

    now = datetime.now(timezone.utc)
    created, warmed, failed = 0, 0, []
    live_events, upcoming_events = 0, 0

    for i, ev in enumerate(events):
        event_time = datetime.fromisoformat(ev["eventTime"].replace("Z", "+00:00"))
        # 每 5 個放 1 個「尚未開賣」,讓前台同時看得到倒數與可搶購兩種狀態
        upcoming = (i % 5 == 0)
        if upcoming:
            start = now + timedelta(days=(i % 7) + 1, hours=3)
            upcoming_events += 1
        else:
            start = now - timedelta(hours=2)
            live_events += 1
        end = event_time - timedelta(days=1)   # 演出前一天截止

        for j, (name, base_price, stock) in enumerate(layout_for(ev["venue"])):
            price = base_price + (i % 5) * 100
            try:
                tt = call("POST", "/admin/ticket-types", token, {
                    "eventId": ev["id"], "name": name, "price": f"{price}.00",
                    "totalStock": stock, "seckillStart": iso(start), "seckillEnd": iso(end),
                })["data"]
                created += 1
                call("POST", f"/admin/ticket-types/{tt['id']}/warmup", token)
                warmed += 1
            except Exception as exc:  # noqa: BLE001 - 逐筆記錄,一筆失敗不停整批
                failed.append((ev["title"], name, repr(exc)))

    out = sys.stdout
    out.write("活動 %d | 建立票種 %d | warmup 成功 %d | 失敗 %d\n"
              % (len(events), created, warmed, len(failed)))
    out.write("開賣中 %d 個活動 / 尚未開賣 %d 個活動\n" % (live_events, upcoming_events))
    for title, name, why in failed:
        out.write("  失敗 %s / %s -> %s\n" % (title, name, why))


if __name__ == "__main__":
    main()
