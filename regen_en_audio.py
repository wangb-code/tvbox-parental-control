# -*- coding: utf-8 -*-
"""只重合成语文题语音（240 条），其他科目语音保持不变。"""
import os, json, io, asyncio
import edge_tts
from gen_assets import digest, AUDIO, MANIFEST, fetch_async, generate_all, baidu_fallback

BASE = os.path.dirname(os.path.abspath(__file__))


def main():
    tasks_map = json.load(io.open(os.path.join(BASE, "en_voice_tasks.json"), encoding="utf-8"))
    tasks = list(tasks_map.items())
    manifest = json.load(io.open(MANIFEST, encoding="utf-8")) if os.path.isfile(MANIFEST) else {}
    stale = [(n, t) for n, t in tasks if manifest.get(n) != digest(t)]
    print("英文语音需重合成:", len(stale), "/", len(tasks))
    results = asyncio.run(generate_all(stale, manifest))
    ok = bad = 0
    for name, size, status in results:
        if status.startswith("FAIL"):
            bad += 1
            print("  FAIL", name, status)
        else:
            ok += 1
    # 更新 manifest 对应的指纹
    for name, text in tasks:
        manifest[name] = digest(text)
    with io.open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("完成: 成功", ok, "失败", bad)
    # 校验全部音频
    missing, tiny, fp = [], [], []
    for name, text in tasks:
        p = os.path.join(AUDIO, name + ".mp3")
        if not os.path.isfile(p):
            missing.append(name)
        elif os.path.getsize(p) < 2000:
            tiny.append(name)
        if manifest.get(name) != digest(text):
            fp.append(name)
    print("校验: 缺失", missing or "无", "过小", tiny or "无", "指纹不符", fp or "无")
    if missing or tiny or fp or bad:
        raise SystemExit("语音校验未通过")


if __name__ == "__main__":
    main()
