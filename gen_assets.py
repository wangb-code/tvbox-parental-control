# -*- coding: utf-8 -*-
"""Build the fixed quiz bank and its Mandarin voice clips for the on-box APK.

Design note for pre-schoolers: the text shown on TV and the text that is read
aloud are deliberately different. A child who cannot read still hears a full
question plus every option. Pinyin / English options are spelled out with
homophone Chinese characters so the TTS engine never has to guess how a latin
letter is pronounced.
"""
import os
import sys
import json
import random
import asyncio
import hashlib
import urllib.parse
import urllib.request
import edge_tts

VOICE = "zh-CN-XiaoxiaoNeural"
RATE = "-8%"

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "apk", "assets")
AUDIO = os.path.join(OUT, "audio")
os.makedirs(AUDIO, exist_ok=True)
MANIFEST = os.path.join(AUDIO, "_manifest.json")


def digest(text):
    """Bind every mp3 to the exact sentence it was synthesised from.

    Question ids are reassigned after shuffle, so a rebuilt bank can silently
    reuse a clip recorded for a different question. Hashing the text prevents
    that class of mismatch permanently.
    """
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


def load_manifest():
    try:
        with open(MANIFEST, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}

CN = "零一二三四五六七八九十"
WORDS = [
    ("apple", "苹果"), ("banana", "香蕉"), ("cat", "猫"), ("dog", "狗"),
    ("egg", "鸡蛋"), ("fish", "鱼"), ("book", "书"), ("ball", "球"),
    ("sun", "太阳"), ("moon", "月亮"), ("milk", "牛奶"), ("bird", "鸟"),
]
# pinyin letter -> homophone used by the voice track
LETTER_SOUND = {
    "a": "啊", "o": "喔", "e": "鹅", "i": "衣", "u": "乌",
    "b": "波", "p": "坡", "m": "摸", "f": "佛", "d": "得",
    "t": "特", "n": "讷", "l": "勒", "g": "哥", "k": "科", "h": "喝",
    "j": "鸡", "q": "七", "x": "西",
    "ba": "八", "pa": "趴", "ma": "妈", "fa": "发", "da": "搭",
    "ta": "他", "na": "拿", "la": "拉", "ge": "哥", "ke": "科", "he": "喝",
    "bo": "波", "po": "坡", "mo": "摸", "fo": "佛", "ji": "鸡",
    "qi": "七", "xi": "西", "du": "读", "tu": "图", "nu": "努", "lu": "路",
    "gu": "姑", "ku": "哭", "hu": "呼",
}
FIRST = "第一声", "第二声", "第三声", "第四声"


def num(n):
    if 0 <= n <= 10:
        return CN[n]
    if 10 < n < 20:
        return "十" + CN[n - 10]
    return str(n)


def pick4(correct, pool, rng):
    """Four distinct options, correct one placed at a pseudo-random slot."""
    others = [x for x in pool if str(x) != str(correct)]
    rng.shuffle(others)
    opts = [str(x) for x in others[:3]]
    while len(opts) < 3:
        opts.append(str(len(opts) + 100))
    opts.insert(rng.randrange(4), str(correct))
    return opts, opts.index(str(correct))


def build():
    rng = random.Random(20261107)
    qs = []

    def add(subject, q, correct, pool, say_q, say_opt):
        opts, ai = pick4(correct, pool, rng)
        voice = say_q + "。" + "。".join(
            "%s，%s" % ("选项" + CN[i + 1], say_opt(o)) for i, o in enumerate(opts))
        qs.append({"subject": subject, "q": q, "options": opts,
                   "answer": ai, "voice": voice + "。",
                   "big": False, "colors": None, "title": None,
                   "qcolor": None})

    def add_visual(title, q, items, correct_index, say_q, qcolor=None):
        """Picture question built from plain Unicode geometry glyphs.

        Using glyphs instead of bitmap assets keeps the APK small and avoids
        missing-emoji glyphs on TV firmwares. Each item is
        (display_text, spoken_text, hex_colour_or_None).
        """
        order = list(range(len(items)))
        rng.shuffle(order)
        shown = [items[i] for i in order]
        opts = [x[0] for x in shown]
        cols = [x[2] for x in shown]
        ai = order.index(correct_index)
        voice = say_q + "。" + "。".join(
            "%s，%s" % ("选项" + CN[i + 1], x[1]) for i, x in enumerate(shown))
        qs.append({"subject": "图形", "q": q, "options": opts,
                   "answer": ai, "voice": voice + "。",
                   "big": True, "colors": cols, "title": title,
                   "qcolor": qcolor})

    # ---------------- math ----------------
    for a in range(1, 6):
        for b in (2, 4):
            add("数学", "%d + %d = ?" % (a, b), a + b, list(range(0, 11)),
                "%s加%s等于几" % (num(a), num(b)), lambda o: num(int(o)))
    for a, b in [(5, 2), (6, 3), (7, 2), (8, 3), (9, 4), (10, 5), (7, 5), (9, 2)]:
        add("数学", "%d - %d = ?" % (a, b), a - b, list(range(0, 11)),
            "%s减%s等于几" % (num(a), num(b)), lambda o: num(int(o)))
    for n in (4, 6, 8):
        add("数学", "数一数，有几个圆点？  " + ("● " * n), n, list(range(1, 11)),
            "数一数，屏幕上一共有几个圆点", lambda o: num(int(o)) + "个")
    for x, y in [(3, 8), (6, 2), (9, 5)]:
        add("数学", "哪个数字更大？  %d   %d" % (x, y), max(x, y), list(range(1, 10)),
            "仔细听，%s和%s，哪个数字更大" % (num(x), num(y)), lambda o: num(int(o)))

    # ---------------- english (Chinese options, one spoken English word) ----
    for w, zh in WORDS[:10]:
        add("英语", "%s  是什么意思？" % w, zh, [x[1] for x in WORDS],
            "英文单词%s是什么意思呢" % w, lambda o: str(o))
    for w, zh in WORDS[10:]:
        add("英语", "「%s」是什么意思？" % w, zh, [x[1] for x in WORDS],
            "英文单词%s是什么意思呢" % w, lambda o: str(o))
    # Reverse direction: show a Chinese word, pick the matching English word.
    # The spoken question must ask exactly what the on-screen options answer.
    for zh in ["苹果", "猫", "鱼", "太阳"]:
        w = dict((v, k) for k, v in WORDS)[zh]
        add("英语", "「%s」的英文是？" % zh, w, [x[0] for x in WORDS],
            "%s的英文单词是哪一个呢" % zh, lambda o: str(o))

    # ---------------- pinyin (letters shown, homophones spoken) ------------
    for target in list("aoeiu"):
        add("拼音", "听一听，哪个是「%s」？" % LETTER_SOUND[target], target,
            list("aoeiubpmf"), "听一听，%s，是下面哪一个字母" % LETTER_SOUND[target],
            lambda o: LETTER_SOUND.get(str(o), str(o)))
    for syl in ["ba", "ma", "da", "ge", "hu", "xi", "lu", "po", "na"]:
        b, rest = syl[0], syl[1:]
        add("拼音", "拼一拼：%s + %s = ?" % (b, rest), syl,
            [x for x in LETTER_SOUND if len(x) == 2],
            "拼一拼，%s和%s，拼起来读什么" % (LETTER_SOUND.get(b, b), LETTER_SOUND.get(rest, rest)),
            lambda o: LETTER_SOUND.get(str(o), str(o)))
    add("拼音", "mā 是第几声？", "第一声", FIRST,
        "听一听，妈，是第一声还是第几声呢", lambda o: str(o))

    # ---------------- picture -> English word (object images) --------------
    # Kid sees an object picture and picks the English word. Images live in
    # apk/assets/images/*.png (Twemoji emoji graphics, CC-BY 4.0).
    ITEMS = [
        ("apple", "苹果", "apple"), ("banana", "香蕉", "banana"),
        ("cat", "猫", "cat"), ("dog", "狗", "dog"),
        ("car", "汽车", "car"), ("ball", "球", "ball"),
        ("book", "书", "book"), ("cup", "杯子", "cup"),
        ("fish", "鱼", "fish"), ("bird", "鸟", "bird"),
        ("flower", "花", "flower"), ("sun", "太阳", "sun"),
        ("moon", "月亮", "moon"), ("star", "星星", "star"),
        ("tree", "树", "tree"), ("house", "房子", "house"),
    ]
    pool_en = [x[2] for x in ITEMS]
    for img, zh, en in ITEMS:
        opts, ai = pick4(en, pool_en, rng)
        voice = "这是什么？用英语怎么说？" + "。" + "。".join(
            "%s，%s" % ("选项" + CN[i + 1], o) for i, o in enumerate(opts))
        qs.append({"subject": "图形", "q": "这是什么？用英语怎么说？",
                   "options": opts, "answer": ai, "voice": voice + "。",
                   "big": False, "colors": None, "title": "看图 · 选英文",
                   "qcolor": None, "image": img})

    rng.shuffle(qs)
    for i, q in enumerate(qs):
        q["id"] = "q%03d" % (i + 1)
    # Pinyin syllable questions show the simple homophone characters already
    # spoken in the voice track, so pre-schoolers can pick by ear. The option
    # positions (and therefore the answer index) are unchanged.
    for q in qs:
        if q["q"].startswith("拼一拼"):
            q["options"] = [LETTER_SOUND.get(str(o), str(o)) for o in q["options"]]

    return qs


# Only clips the app actually plays are kept. The old intro/hint/timeout
# narrations were removed together with the retired QuizActivity and the
# "how to use the remote" explanation (v6.0). lock_* / unlock_ready are
# referenced dynamically as "lock_" + reason in ControlService.tick().
COMMON = {
    "right": "答对啦，真棒！可以继续看动画片啦。",
    "wrong": "再想一想哦，没关系，我们再听一遍。",
    # Spoken when a hard lock STARTS, so a pre-schooler who cannot read the
    # lock screen still understands why the TV stopped and when it comes back.
    "lock_rest": "看的时间到啦，现在要休息一会儿，让眼睛歇一歇。休息好了，电视会自动打开哦。",
    "lock_daily": "今天看电视的时间用完啦，明天再看好不好呀。现在去玩点别的吧。",
    "lock_penalty": "哎呀，答错太多啦。我们先休息一下，等一会儿再重新做一道题，要认真想一想哦。",
    "lock_manual": "电视先休息一下啦，请爸爸妈妈来解锁哦。",
    "unlock_ready": "休息好啦，可以继续看啦，记得保护眼睛哦。",
}

# Announced immediately before the LAST quiz of a session. The minute
# number is baked into the clip because the box has no reliable online TTS.
for _n in range(1, 11):
    COMMON["last_%d" % _n] = (
        "还有 %s 分钟就到时间啦，这是最后一题哦。" % CN[_n])
COMMON["last_more"] = "这轮快到时间啦，这是最后一题哦。"

BAIDU = "https://fanyi.baidu.com/gettts?lan=zh&text=%s&spd=3&source=web"


def baidu_fallback(name, text):
    """Plain HTTP fallback in case the neural TTS endpoint is unreachable."""
    path = os.path.join(AUDIO, name + ".mp3")
    try:
        req = urllib.request.Request(BAIDU % urllib.parse.quote(text),
                                     headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=25) as r:
            data = r.read()
        if len(data) < 2000 or data[:2] == b"<!":
            raise ValueError("bad audio %d bytes" % len(data))
        with open(path, "wb") as f:
            f.write(data)
        return name, len(data), "ok-baidu"
    except Exception as e:
        return name, 0, "FAIL " + str(e)[:70]


async def fetch_async(item, sem, manifest):
    name, text = item
    path = os.path.join(AUDIO, name + ".mp3")
    # Only reuse a clip when its recorded text still matches this question.
    if (manifest.get(name) == digest(text)
            and os.path.isfile(path) and os.path.getsize(path) > 2000):
        return name, os.path.getsize(path), "cached"
    async with sem:
        for attempt in range(2):
            try:
                c = edge_tts.Communicate(text, VOICE, rate=RATE)
                await c.save(path)
                size = os.path.getsize(path)
                if size < 2000:
                    raise ValueError("tiny audio")
                return name, size, "ok"
            except Exception as e:
                if attempt == 1:
                    return baidu_fallback(name, text)
                await asyncio.sleep(1)
    return name, 0, "fail"


async def generate_all(tasks, manifest):
    sem = asyncio.Semaphore(5)
    return await asyncio.gather(*(fetch_async(t, sem, manifest) for t in tasks))


def main():
    # --audio-only keeps the curated bank (ids, subjects, answer slots) and
    # only reconciles the voice clips. A full build would reshuffle every id.
    audio_only = "--audio-only" in sys.argv
    qpath = os.path.join(OUT, "questions.json")
    if audio_only:
        with open(qpath, encoding="utf-8") as f:
            qs = json.load(f)["questions"]
        print("校验模式：沿用现有题库 %d 道，不重建题目" % len(qs))
    else:
        qs = build()
        with open(qpath, "w", encoding="utf-8") as f:
            json.dump({"questions": qs}, f, ensure_ascii=False, indent=1)
    from collections import Counter
    print("题库 %d 道  科目分布: %s" % (len(qs), dict(Counter(q["subject"] for q in qs))))
    print("样例:", json.dumps(qs[0], ensure_ascii=False)[:200])
    tasks = []
    for q in qs:
        variants = q.get("variants")
        if variants:
            # One clip per option order: screen and voice must always agree.
            for v in variants:
                tasks.append((v["voice_id"], v["voice"]))
        else:
            tasks.append((q["id"], q["voice"]))
    tasks += list(COMMON.items())
    manifest = load_manifest()
    wanted = dict((n, digest(t)) for n, t in tasks)
    # Clips whose source sentence changed are simply re-synthesised in place.
    # (Deleting them in bulk is blocked by the workspace file guard, and is
    # unnecessary: every stale name is overwritten below.)
    stale = [n for n, h in wanted.items() if manifest.get(n) != h]
    print("需要重新合成 %d / %d 条语音（%s）..."
          % (len(stale), len(tasks), VOICE))
    results = asyncio.run(generate_all(tasks, manifest))
    ok = bad = 0
    fresh = 0
    for name, size, status in results:
        if status.startswith("FAIL"):
            bad += 1
            print("  FAIL", name, status)
        else:
            ok += 1
            if status != "cached":
                fresh += 1
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(dict((n, wanted[n]) for n, s, st in results
                       if not st.startswith("FAIL")),
                  f, ensure_ascii=False, indent=1)
    print("语音完成: %d 成功（新合成 %d）, %d 失败" % (ok, fresh, bad))

    # Retire clips that no longer belong to any question or code path.
    # Move them out of assets/ instead of bulk-deleting: the workspace file
    # guard blocks large delete batches, and a rename keeps a rollback copy.
    # legacy_audio/ is outside the packaged assets, so the APK stays clean.
    wanted = dict(tasks)
    removed = []
    legacy = os.path.join(BASE, "legacy_audio")
    for fn in sorted(os.listdir(AUDIO)):
        if not fn.endswith(".mp3"):
            continue
        if fn[:-4] not in wanted:
            try:
                os.makedirs(legacy, exist_ok=True)
                os.replace(os.path.join(AUDIO, fn), os.path.join(legacy, fn))
                removed.append(fn)
            except Exception as e:
                print("  无法移出", fn, e)
    print("移出未使用语音 %d 个: %s" % (len(removed), ", ".join(removed[:6]) or "无"))

    # Full audit: every required clip exists, is playable and was synthesised
    # from exactly the sentence currently shipped in questions.json.
    with open(MANIFEST, encoding="utf-8") as f:
        final_manifest = json.load(f)
    missing, stale_fp, tiny = [], [], []
    for name, text in tasks:
        p = os.path.join(AUDIO, name + ".mp3")
        if not os.path.isfile(p):
            missing.append(name)
        elif os.path.getsize(p) < 2000:
            tiny.append(name)
        if final_manifest.get(name) != digest(text):
            stale_fp.append(name)
    orphans = [fn for fn in os.listdir(AUDIO)
               if fn.endswith(".mp3") and fn[:-4] not in wanted]
    print("语音校验: 应有 %d 条 | 缺失 %s | 指纹不符 %s | 过小 %s | 多余 %s"
          % (len(tasks), missing or "无", stale_fp or "无", tiny or "无", orphans or "无"))
    total = sum(os.path.getsize(os.path.join(AUDIO, f))
                for f in os.listdir(AUDIO) if f.endswith(".mp3"))
    print("音频总大小: %.2f MB" % (total / 1048576.0))
    if missing or stale_fp or tiny or orphans or bad:
        raise SystemExit("语音校验未通过，请检查上面的清单")


if __name__ == "__main__":
    main()
