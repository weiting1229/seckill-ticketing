# -*- coding: utf-8 -*-
"""修正兩筆標題含空主題的活動。

成因:內容包裡有 6 個藝人的 tourThemes 陣列非空、但其中一個語言的字串是空的
(例 ethan-lin 是 {"en": "First Run", "zh": ""})。seed 腳本只檢查「陣列非空」
就套用該語言的主題,於是產出「林予安 「」巡迴演唱會」與「Ophelia —  Tour 2026」。

⚠ 這也解釋了先前發現的「ethan-lin 有主題但海報沒有 themeIndex」——
poster-forge 烤圖時該語言沒有文字可放。
"""
from _common import announce, admin_token, call


FIXES = {
    "林予安 「」巡迴演唱會": ("林予安 2026 巡迴演唱會", "林予安 睽違兩年的巡迴,首站台北小巨蛋。"),
    "Ophelia —  Tour 2026": ("Ophelia — Live in Taipei 2026", "Ophelia live in Taipei. One night only."),
}




def main():
    announce("修正標題含空主題的活動")
    token = admin_token()

    page, done = 1, []
    while True:
        res = call("GET", f"/admin/events?page={page}&size=100", token)["data"]
        for ev in res["items"]:
            if ev["title"] in FIXES:
                new_title, new_desc = FIXES[ev["title"]]
                call("PUT", f"/admin/events/{ev['id']}", token, {
                    "title": new_title, "description": new_desc, "venue": ev["venue"],
                    "coverImageUrl": "", "featured": ev["featured"],
                    "featuredOrder": ev["featuredOrder"], "eventTime": ev["eventTime"],
                    "status": ev["status"],
                })
                done.append((ev["title"], new_title))
        if page * res["size"] >= res["total"]:
            break
        page += 1

    for old, new in done:
        print(f"修正:{old!r} -> {new!r}")
    print(f"共 {len(done)} 筆")


if __name__ == "__main__":
    main()
