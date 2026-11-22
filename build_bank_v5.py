# -*- coding: utf-8 -*-
"""v7.10：仅重建语文题为「一年级常用字」难度。

题目=带调拼音，选项=一年级常用汉字（读音互不相同），语音逐读四个选项。
其余科目（英语/数学/图形）沿用当前 questions.json，不改动、不重新合成语音。
"""
import os, json, io, random, hashlib
from collections import Counter
import grade1_friendly as G

BASE = os.path.dirname(os.path.abspath(__file__))
QS = os.path.join(BASE, "apk", "assets", "questions.json")
CN = "零一二三四五六七八九十"


def digest(t):
    return hashlib.sha1(t.encode("utf-8")).hexdigest()[:16]


def pick4(correct, pool, rng):
    others = [x for x in pool if x != correct]
    rng.shuffle(others)
    opts = others[:3]
    while len(opts) < 3:
        opts.append("?")
    opts.insert(rng.randrange(4), correct)
    return opts, opts.index(correct)


def make_variants(q, rng):
    import re
    parts = [p for p in q["voice"].split("。") if p.strip()]
    say_q = parts[0]
    sounds = []
    for p in parts[1:]:
        m = re.match(r"^选项[一二三四]，(.+)$", p)
        sounds.append(m.group(1) if m else p)
    opts, ai = q["options"], q["answer"]
    perms = [list(range(4))]
    slots = [i for i in range(4) if i != ai]
    rng.shuffle(slots)
    for slot in slots:
        others = [i for i in range(4) if i != ai]
        rng.shuffle(others)
        perm = others[:]
        perm.insert(slot, ai)
        perms.append(perm)
    out = []
    for vi, perm in enumerate(perms):
        new_opts = [opts[k] for k in perm]
        new_sounds = [sounds[k] for k in perm]
        voice = say_q + "。".join(
            "选项%s，%s" % (CN[i + 1], s) for i, s in enumerate(new_sounds)) + "。"
        out.append({"options": new_opts, "answer": perm.index(ai),
                    "voice": voice, "voice_id": q["id"] + "_" + str(vi)})
    return out


def build_pinyin(rng, m, total=60):
    """题目=带调拼音，四个选项=一年级常用字，读音互不相同。"""
    # 只保留有常用字的带调拼音
    pys = sorted(m.keys())
    rng.shuffle(pys)
    out = []
    for py in pys:
        if len(out) >= total:
            break
        correct = m[py][0]
        # 干扰项：读音不同的其他一年级字
        others = [p for p in pys if p != py]
        rng.shuffle(others)
        chars = []
        seen = {correct}
        for op in others:
            if len(chars) >= 3:
                break
            oc = m[op][0]
            if oc not in seen:
                chars.append(oc)
                seen.add(oc)
        if len(chars) < 3:
            continue
        opts, ai = pick4(correct, chars + [correct], rng)  # 已含 correct
        # pick4 会把 correct 再插一次，重新构造更稳妥
        candidates = [correct] + chars
        rng.shuffle(candidates)
        opts = candidates
        ai = opts.index(correct)
        voice = "听一听，哪个字和屏幕上的拼音一样呢。" + "".join(
            "选项%s，%s。" % (CN[i + 1], c) for i, c in enumerate(opts))
        out.append({"subject": "语文", "q": "哪个字读「%s」？" % py,
                    "options": opts, "answer": ai, "voice": voice,
                    "big": False, "colors": None, "title": "听拼音 · 选汉字",
                    "qcolor": None})
    return out


def main():
    data = json.load(io.open(QS, encoding="utf-8"))
    qs = data["questions"]
    other = [q for q in qs if q["subject"] != "语文"]
    old_yw = [q for q in qs if q["subject"] == "语文"]
    print("原有语文题:", len(old_yw), "其他科目:", len(other))

    rng = random.Random(20261122)
    m = G.build_map()
    yw = build_pinyin(rng, m, 60)
    assert len(yw) == 60, "一年级字库不足，仅生成 %d 题" % len(yw)

    # 生成变体：题目 id 沿用旧语文题 id（保持启用/错题结构稳定）
    ids = [q["id"] for q in old_yw]
    for q, qid in zip(yw, ids):
        q["id"] = qid
        q["variants"] = make_variants(q, rng)

    # 一致性校验
    errs = []
    for q in yw:
        base = q["options"][q["answer"]]
        if len(q["options"]) != 4 or len(set(q["options"])) != 4:
            errs.append(q["id"] + "-dup")
        for v in q["variants"]:
            if v["options"][v["answer"]] != base:
                errs.append(q["id"] + "-drift")
    if errs:
        raise SystemExit("校验失败:" + str(errs[:6]))

    # 合并：语文题放回原 id 位置，其他不变
    new_by_id = {q["id"]: q for q in yw}
    for i, q in enumerate(qs):
        if q["subject"] == "语文":
            qs[i] = new_by_id[q["id"]]

    json.dump(data, io.open(QS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("重建完成: 语文 60 题（一年级常用字），科目分布:",
          dict(Counter(q["subject"] for q in qs)))
    for q in yw[:5]:
        print("  ", q["q"], q["options"], "->", q["options"][q["answer"]])
    # 输出需要重合成的语音名
    names = set()
    for q in yw:
        for v in q["variants"]:
            names.add((v["voice_id"], v["voice"]))
    with io.open(os.path.join(BASE, "yw_voice_tasks.json"), "w", encoding="utf-8") as f:
        json.dump(dict(names), f, ensure_ascii=False)
    print("需重合成语文语音:", len(names), "条")


if __name__ == "__main__":
    main()
