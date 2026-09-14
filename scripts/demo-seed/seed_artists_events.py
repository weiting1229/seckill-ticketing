# -*- coding: utf-8 -*-
"""為 content-pack 裡的每一個藝人各建一個活動,讓 46 張海報全部出現在前台。

## 為什麼原本只有 6 筆

seckill 的首頁列的是**活動(events)**,不是藝人。46 張海報掛在 `artists` 表上,
一個藝人要在首頁出現,必須有一個「標題含它團名」的活動存在 —— 而那正是 seeder
(2026-08-17-demo-event-seeder.md)的職責,那份計畫**完全未實作**。
上一輪只建了 6 筆是為了驗證比對邏輯,不是上限。

這支腳本做的就是 seeder 之後會自動做的事:一個藝人一個活動。
"""
import sys

from _common import announce, admin_token, call


VENUES = [
    "台北小巨蛋", "高雄流行音樂中心", "Legacy Taipei", "台中圓滿戶外劇場",
    "THE WALL 公館", "台北國際會議中心", "新莊體育館", "台南文化中心",
    "Zepp New Taipei", "河岸留言西門紅樓", "桃園國際棒球場", "花蓮東大門廣場",
]

# 2026-10 ~ 2027-03,全部落在未來(今天 2026-09-07),讓卡片都是「即將登場」
SLOTS = [(2026, 10), (2026, 11), (2026, 12), (2027, 1), (2027, 2), (2027, 3)]




def compose(artist, venue):
    """標題刻意做成真實活動的樣子,不是「標題 = 團名」——
    比對靠的是子字串 + 拉丁詞邊界,不是相等。"""
    theme = artist["tourThemes"][0] if artist["tourThemes"] else None
    if artist["nameZh"]:
        name = artist["nameZh"]
        if theme:
            title = f"{name} 「{theme['zh']}」巡迴演唱會"
            desc = f"{name} 帶著「{theme['zh']}」巡迴來到{venue}。全場曲目重新編排,現場限定。"
        else:
            title = f"{name} 2026 巡迴演唱會"
            desc = f"{name} 睽違兩年的巡迴,首站{venue}。"
    else:
        name = artist["nameEn"]
        if theme:
            title = f"{name} — {theme['en']} Tour 2026"
            desc = f"{name} brings the {theme['en']} tour to {venue}. One night only."
        else:
            title = f"{name} — Live in Taipei 2026"
            desc = f"{name} live at {venue}. One night only."
    return title, desc


def main():
    announce("為每個藝人建立一個活動")
    token = admin_token()

    artists = call("GET", "/artists")["data"]
    # 依 slug 排序讓每次執行的場地/日期分配都一樣(可重跑、結果可預期)
    artists.sort(key=lambda a: a["slug"])

    featured_seq = 0
    created, failed = [], []
    for i, a in enumerate(artists):
        venue = VENUES[i % len(VENUES)]
        title, desc = compose(a, venue)
        year, month = SLOTS[i % len(SLOTS)]
        day = 3 + (i * 7) % 25
        is_featured = a["tier"] == "FEATURED"
        try:
            ev = call("POST", "/admin/events", token, {
                "title": title,
                "description": desc,
                "venue": venue,
                "coverImageUrl": "",   # 留空:走「標題比對藝人海報」那一層
                "featured": is_featured,
                "featuredOrder": featured_seq if is_featured else None,
                "eventTime": f"{year}-{month:02d}-{day:02d}T11:00:00Z",
            })["data"]
            if is_featured:
                featured_seq += 1
            call("PUT", f"/admin/events/{ev['id']}", token, {
                "title": ev["title"], "description": ev["description"], "venue": ev["venue"],
                "coverImageUrl": "", "featured": ev["featured"],
                "featuredOrder": ev["featuredOrder"], "eventTime": ev["eventTime"],
                "status": "PUBLISHED",
            })
            created.append((a["slug"], title, a["tier"]))
        except Exception as exc:  # noqa: BLE001 - 逐筆記錄,不讓一筆失敗停掉整批
            failed.append((a["slug"], title, repr(exc)))

    sys.stdout.write("建立 %d / 失敗 %d(FEATURED %d)\n" % (
        len(created), len(failed), sum(1 for _, _, t in created if t == "FEATURED")))
    for slug, title, why in failed:
        sys.stdout.write("  失敗 %s %s -> %s\n" % (slug, title, why))


if __name__ == "__main__":
    main()
