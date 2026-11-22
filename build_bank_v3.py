# -*- coding: utf-8 -*-
"""v7.6 题库重构：150 题 = 拼音60 + 英语50 + 数学24 + 图形16。

设计要点
- 拼音题：声母 + 单韵母(a o e i u ü) 带四声拼读，两种题型各 30：
    A) 听发音选带调拼音：语音读标准音节音，四个带调拼音选项；
    B) 看 b + ā 选拼读结果：屏幕给声母+韵母，选带调音节。
  语音一律由「同音汉字」驱动，听感即标准拼音发音；屏幕显示带调拼音。
- 英语题：A-Z 字母开头的代表单词，自然拼读为主：
    A) 看字母首字母选单词；B) 听单词首字母选字母。
- 数学题：20 以内加减（含三数连加），难度与上一版完全一致。
- 图形题：沿用看图选英文 16 题与图片资源。
- 每题 4 套选项顺序变体，各配独立语音，保证"选项N"读音与屏幕一致。

输出 apk/assets/questions.json，并清空旧的语音清单（由 gen_assets.py --audio-only 重建）。
"""
import os
import json
import random
import hashlib
from collections import Counter

import pinyin_map as PM

BASE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(BASE, "apk", "assets")
QS = os.path.join(ASSETS, "questions.json")
CN = "零一二三四五六七八九十"


def digest(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


def pick4(correct, pool, rng):
    """四个互不相同的选项，随机放置正确答案。"""
    others = [x for x in pool if str(x) != str(correct)]
    rng.shuffle(others)
    opts = [str(x) for x in others[:3]]
    while len(opts) < 3:
        opts.append("?%d" % len(opts))
    opts.insert(rng.randrange(4), str(correct))
    return opts, opts.index(str(correct))


def make_variants(q, rng):
    """生成 4 套选项顺序，每套语音的『选项N』与实际选项逐项对齐。"""
    import re
    parts = [p for p in q["voice"].split("。") if p.strip()]
    say_q = parts[0]
    sounds = []
    for p in parts[1:]:
        m = re.match(r"^选项[一二三四]，(.+)$", p)
        sounds.append(m.group(1) if m else p)  # 去掉已有"选项N，"前缀
    assert len(sounds) == 4, (q["id"], q["voice"])
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
        voice = say_q + "。" + "。".join(
            "选项%s，%s" % (CN[i + 1], s) for i, s in enumerate(new_sounds)) + "。"
        out.append({
            "options": new_opts,
            "answer": perm.index(ai),
            "voice": voice,
            "voice_id": q["id"] + "_" + str(vi),
        })
    return out


# ------------------------------------------------------------------ 数学
def num(n):
    if 0 <= n <= 10:
        return CN[n]
    if 10 < n < 20:
        return "十" + CN[n - 10]
    if n == 20:
        return "二十"
    return str(n)


def build_math(rng):
    """24 题：10 以内加、10 以内三数连加、20 以内加减。难度不变。"""
    items = []
    seen = set()

    def add(nums, op):
        expr = (" + " if op == "+" else " - ").join(str(x) for x in nums)
        q = expr + " = ?"
        if op == "+":
            correct = sum(nums)
            say = "加".join(num(x) for x in nums) + "等于几"
        else:
            correct = nums[0] - sum(nums[1:])
            say = "减".join(num(x) for x in nums) + "等于几"
        items.append((q, correct, say))

    # 10 以内加法 6
    while sum(1 for x in items if True) < 6:
        a, b = rng.randint(1, 9), rng.randint(1, 9)
        if a + b > 10 or (a, b, "+") in seen:
            continue
        seen.add((a, b, "+"))
        add([a, b], "+")
    # 三数连加（和<=10）5
    chain = 0
    while chain < 5:
        a, b, c = rng.randint(1, 6), rng.randint(1, 6), rng.randint(1, 6)
        if a + b + c > 10 or (a, b, c) in seen:
            continue
        seen.add((a, b, c))
        add([a, b, c], "+")
        chain += 1
    # 20 以内加法 6
    plus = 0
    while plus < 6:
        a, b = rng.randint(2, 18), rng.randint(2, 18)
        t = a + b
        if not (11 <= t <= 20) or (a < 6 and b < 6) or (a, b, "+") in seen:
            continue
        seen.add((a, b, "+"))
        add([a, b], "+")
        plus += 1
    # 20 以内减法 7
    minus = 0
    while minus < 7:
        a = rng.randint(8, 20)
        b = rng.randint(1, a)
        if (a, b, "-") in seen:
            continue
        seen.add((a, b, "-"))
        add([a, b], "-")
        minus += 1

    slots = [0, 1, 2, 3] * 6
    rng.shuffle(slots)
    out = []
    for (q, correct, say), slot in zip(items, slots):
        near = [x for x in range(0, 21) if x != correct and abs(x - correct) <= 5]
        rng.shuffle(near)
        others = [str(x) for x in near[:3]]
        while len(others) < 3:
            v = str(rng.randint(0, 20))
            if v != str(correct) and v not in others:
                others.append(v)
        opts = others[:]
        opts.insert(slot, str(correct))
        voice = say + "。" + "。".join(
            "选项%s，%s" % (CN[i + 1], num(int(o))) for i, o in enumerate(opts)) + "。"
        out.append({"subject": "数学", "q": q, "options": opts, "answer": slot,
                    "voice": voice, "big": False, "colors": None,
                    "title": None, "qcolor": None})
    return out


# ------------------------------------------------------------------ 语文（拼音）
def _distinct_hanzi(key):
    """该音节四声同音字是否两两不同。"""
    tones = PM.SYLLABLE[key]
    hz = [tones[t][1] for t in (1, 2, 3, 4) if t in tones]
    return len(hz) == 4 and len(set(hz)) == 4


def build_pinyin(rng, total=60):
    """题目=带调拼音，四个选项=中文汉字，选读音对应的字。

    语音读出题干拼音（用同音汉字驱动，听感为标准音节发音），再逐个读
    四个中文选项。孩子听音辨字，选项均为常用汉字。
    """
    entries = [e for e in PM.all_syllables() if _distinct_hanzi(e[0])]
    rng.shuffle(entries)
    out = []
    used_py = set()

    for key, tone0, py0, hz0 in entries:
        if len(out) >= total:
            break
        if py0 in used_py:
            continue
        tones = PM.SYLLABLE[key]
        # 四个选项：不同读音的汉字，正确答案读题干音
        correct_py = py0
        correct_hz = hz0
        distract = []
        seen_py = {correct_py}
        seen_hz = {correct_hz}
        # 先取其他声母、韵母相近的字，读音必须不同
        others = [e for e in entries if e[2] not in seen_py]
        rng.shuffle(others)
        for ek, et, epy, eh in others:
            if len(distract) >= 3:
                break
            if epy in seen_py or eh in seen_hz:
                continue
            seen_py.add(epy)
            seen_hz.add(eh)
            distract.append((epy, eh))
        if len(distract) < 3:
            continue
        candidates = [(correct_py, correct_hz)] + distract
        rng.shuffle(candidates)
        options = [c[1] for c in candidates]          # 屏幕只显示中文汉字
        answer = [c[0] for c in candidates].index(correct_py)
        # 题干不读出答案字本身（否则等于报答案）；只读中性引导，
        # 再由孩子听四个选项的读音，选出与题干带调拼音一致的那个字。
        voice = "听一听，哪个字和屏幕上的拼音一样呢。" + "".join(
            "选项%s，%s。" % (CN[i + 1], c[1]) for i, c in enumerate(candidates))
        out.append({"subject": "语文", "q": "哪个字读「%s」？" % correct_py,
                    "options": options, "answer": answer, "voice": voice,
                    "big": False, "colors": None, "title": "听拼音 · 选汉字",
                    "qcolor": None})
        used_py.add(py0)
    return out


# ------------------------------------------------------------------ 英语（A-Z 字母代表单词）
# A-Z 字母及其代表的简单单词（自然拼读）
EN_WORDS = [
    ("a", "apple", "苹果"), ("b", "ball", "球"), ("c", "cat", "猫"),
    ("d", "dog", "狗"), ("e", "egg", "鸡蛋"), ("f", "fish", "鱼"),
    ("g", "goat", "山羊"), ("h", "hat", "帽子"), ("i", "ice", "冰"),
    ("j", "juice", "果汁"), ("k", "kite", "风筝"), ("l", "lion", "狮子"),
    ("m", "milk", "牛奶"), ("n", "nose", "鼻子"), ("o", "orange", "橙子"),
    ("p", "pig", "猪"), ("q", "queen", "女王"), ("r", "rabbit", "兔子"),
    ("s", "sun", "太阳"), ("t", "tiger", "老虎"), ("u", "umbrella", "雨伞"),
    ("v", "van", "货车"), ("w", "water", "水"), ("x", "box", "盒子"),
    ("y", "yo-yo", "溜溜球"), ("z", "zebra", "斑马"),
]
LETTER_SAY = {
    "a": "诶", "b": "逼", "c": "西", "d": "迪", "e": "伊", "f": "艾弗",
    "g": "吉", "h": "艾尺", "i": "爱", "j": "杰", "k": "开", "l": "艾勒",
    "m": "艾姆", "n": "恩", "o": "哦", "p": "屁", "q": "丘", "r": "阿尔",
    "s": "艾斯", "t": "梯", "u": "优", "v": "维", "w": "达不溜",
    "x": "艾克斯", "y": "歪", "z": "贼德",
}


def build_english(rng, total=50):
    """围绕 A-Z 字母及其代表单词：看字母选单词 / 看单词选小写字母。"""
    out = []
    all_words = [w for _, w, _ in EN_WORDS]
    letters = [c for c, _, _ in EN_WORDS]
    rng.shuffle(letters)
    for idx, letter in enumerate(letters):
        if len(out) >= total:
            break
        word = next(w for c, w, _ in EN_WORDS if c == letter)
        if idx % 2 == 0:
            opts, ai = pick4(word, all_words, rng)
            voice = "哪个单词是以字母%s开头的呢。" % LETTER_SAY[letter] + "".join(
                "选项%s，%s。" % (CN[i + 1], o) for i, o in enumerate(opts))
            out.append({"subject": "英语", "q": "哪个单词以字母 %s 开头？" % letter,
                        "options": opts, "answer": ai, "voice": voice,
                        "big": False, "colors": None, "title": "字母 · 代表单词",
                        "qcolor": None})
        else:
            opts, ai = pick4(letter, letters, rng)   # 小写 a-z
            voice = "单词%s，是以哪个字母开头的呢。" % word + "".join(
                "选项%s，%s。" % (CN[i + 1], LETTER_SAY[o]) for i, o in enumerate(opts))
            out.append({"subject": "英语", "q": "「%s」以哪个字母开头？" % word,
                        "options": opts, "answer": ai, "voice": voice,
                        "big": False, "colors": None, "title": "字母 · 代表单词",
                        "qcolor": None})
    while len(out) < total:
        letter, word, _ = rng.choice(EN_WORDS)
        opts, ai = pick4(word, all_words, rng)
        voice = "哪个单词是以字母%s开头的呢。" % LETTER_SAY[letter] + "".join(
            "选项%s，%s。" % (CN[i + 1], o) for i, o in enumerate(opts))
        out.append({"subject": "英语", "q": "哪个单词以字母 %s 开头？" % letter,
                    "options": opts, "answer": ai, "voice": voice,
                    "big": False, "colors": None, "title": "字母 · 代表单词",
                    "qcolor": None})
    return out


# ------------------------------------------------------------------ 图形
def build_visual(rng):
    """沿用看图选英文 16 题。"""
    items = [
        ("apple", "苹果", "apple"), ("banana", "香蕉", "banana"),
        ("cat", "猫", "cat"), ("dog", "狗", "dog"),
        ("car", "汽车", "car"), ("ball", "球", "ball"),
        ("book", "书", "book"), ("cup", "杯子", "cup"),
        ("fish", "鱼", "fish"), ("bird", "鸟", "bird"),
        ("flower", "花", "flower"), ("sun", "太阳", "sun"),
        ("moon", "月亮", "moon"), ("star", "星星", "star"),
        ("tree", "树", "tree"), ("house", "房子", "house"),
    ]
    pool = [x[2] for x in items]
    out = []
    for img, zh, en in items:
        opts, ai = pick4(en, pool, rng)
        voice = "这是什么？用英语怎么说。" + "".join(
            "选项%s，%s。" % (CN[i + 1], o) for i, o in enumerate(opts))
        out.append({"subject": "图形", "q": "这是什么？用英语怎么说？",
                    "options": opts, "answer": ai, "voice": voice,
                    "big": False, "colors": None, "title": "看图 · 选英文",
                    "qcolor": None, "image": img, "_kind": "visual"})
    return out


# ------------------------------------------------------------------ 校验
def validate(qs):
    errs = []
    ids = [q["id"] for q in qs]
    if len(ids) != len(set(ids)):
        errs.append("重复 id")
    for q in qs:
        if len(q["options"]) != 4 or len(set(q["options"])) != 4:
            errs.append("%s 选项非4个互异" % q["id"])
        if not (0 <= q["answer"] < 4):
            errs.append("%s 答案越界" % q["id"])
        parts = [p for p in q["voice"].split("。") if p.strip()]
        if len(parts) != 5:
            errs.append("%s 语音段数=%d" % (q["id"], len(parts)))
            continue
        for i in range(4):
            if not parts[i + 1].startswith("选项%s，" % CN[i + 1]):
                errs.append("%s 选项读音错位" % q["id"])
                break
        # 每个变体答案必须指向同一正确文本
        for v in q.get("variants", []):
            if v["options"][v["answer"]] != q["options"][q["answer"]]:
                errs.append("%s 变体答案漂移" % q["id"])
                break
    return errs


def main():
    rng = random.Random(20261121)
    math = build_math(rng)
    pinyin = build_pinyin(rng, 60)
    english = build_english(rng, 50)
    visual = build_visual(rng)

    qs = math + pinyin + english + visual
    print("分科:", dict(Counter(q["subject"] for q in qs)))
    print("题型:", dict(Counter(q.get("_kind", "?") for q in qs)))
    assert len(math) == 24 and len(pinyin) == 60 and len(english) == 50 and len(visual) == 16
    assert len(qs) == 150

    rng.shuffle(qs)
    for i, q in enumerate(qs):
        q["id"] = "q%03d" % (i + 1)
        kind = q.pop("_kind", None)
        q["variants"] = make_variants(q, rng)

    errs = validate(qs)
    if errs:
        raise SystemExit("校验失败: %s" % errs[:10])

    order = ("id", "subject", "q", "options", "answer", "voice", "big",
             "colors", "title", "qcolor", "image", "variants")
    for q in qs:
        ordered = {k: q[k] for k in order if k in q}
        q.clear()
        q.update(ordered)

    with open(QS, "w", encoding="utf-8") as f:
        json.dump({"questions": qs}, f, ensure_ascii=False, indent=1)

    # 清空语音指纹清单，强制 gen_assets.py 全量重建
    manifest = os.path.join(ASSETS, "audio", "_manifest.json")
    if os.path.isfile(manifest):
        with open(manifest, "w", encoding="utf-8") as f:
            json.dump({}, f)

    clips = sum(len(q["variants"]) for q in qs) + 1  # +1 占位，COMMON 另计
    print("题库写入完成: %d 题, 变体语音 %d 条" % (len(qs), clips))
    print("样例-拼音听辨:")
    for q in [x for x in qs if x["subject"] == "语文"][:2]:
        print("  ", q["q"], q["options"], "->", q["options"][q["answer"]])
        print("    voice:", q["voice"][:90])
    print("样例-英语:")
    for q in [x for x in qs if x["subject"] == "英语"][:2]:
        print("  ", q["q"], q["options"], "->", q["options"][q["answer"]])
        print("    voice:", q["voice"][:90])


if __name__ == "__main__":
    main()
