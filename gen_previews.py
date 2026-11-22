# -*- coding: utf-8 -*-
"""Regenerate the desktop preview HTML files from the LIVE box.

A plain copy of the on-box pages would issue relative /api/... calls against
the local static preview server, which has no such route, so every preview
showed "服务未响应" even though the box was healthy. Here all API, image and
navigation URLs are rewritten to the absolute box address. The 题库 preview
also gets the real question bank inlined, so it renders offline as well.
"""
import os
import json
import re
import urllib.request

import sys

BASE = os.path.dirname(os.path.abspath(__file__))
# Box address is user-specific: pass as argv[1] or set BOX_URL env var.
BOX = (sys.argv[1] if len(sys.argv) > 1 else
       os.environ.get("BOX_URL", "http://192.168.1.100:8765"))
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

PAGES = {
    "/": "preview_control.html",
    "/changelog": "preview_changelog.html",
    "/bank": "preview_bank.html",
}


def get(path):
    return OPENER.open(BOX + path, timeout=8).read().decode("utf-8")


def rewrite(h):
    h = h.replace("/api/", BOX + "/api/")
    h = h.replace("/img/", BOX + "/img/")
    h = h.replace('src="/', 'src="' + BOX + "/")
    h = h.replace('href="/', 'href="' + BOX + "/")
    h = h.replace("location.href='/", "location.href='" + BOX + "/")
    h = h.replace("location.replace('/", "location.replace('" + BOX + "/")
    h = h.replace(".textContent='服务未响应'", ".textContent='预览数据'")
    h = h.replace('<span class="dot" id="dot"></span>',
                  '<span class="dot on" id="dot"></span>')
    h = h.replace('id="conn" style="font-size:12px">连接中',
                  'id="conn" style="font-size:12px">预览数据')
    # The on-box common.js uses a relative /api/status; when loaded into a
    # desktop preview that URL points at the local static server. Inline the
    # script and point it at the box so the settings form gets real values.
    js = get("/common.js").replace("fetch('/api/status'",
                                   "fetch('" + BOX + "/api/status'")
    h = re.sub(r'<script\s+src="[^"]*common\.js"\s*></script>',
               lambda m: "<script>\n" + js + "\n</script>", h)
    return h


def main():
    for path, fn in PAGES.items():
        h = rewrite(get(path))
        if fn == "preview_bank.html":
            data = json.loads(get("/api/questionbank"))
            empty = ("var BANK={items:[],subjects:[],total:0,enabled:0,"
                     "disabled:0,asked:0,wrong:0,never_asked:0};")
            if empty not in h:
                raise SystemExit("bank.html BANK declaration changed; update this script")
            h = h.replace(empty, "var BANK="
                          + json.dumps(data, ensure_ascii=False) + ";")
        with open(os.path.join(BASE, fn), "w", encoding="utf-8") as f:
            f.write(h)
        print("%-26s %6d bytes  盒子接口 %s" % (fn, len(h), BOX))


if __name__ == "__main__":
    main()
