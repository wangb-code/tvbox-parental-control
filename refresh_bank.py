# -*- coding: utf-8 -*-
"""In-place refresh of the shipped question bank.

Keeps every question id stable (so the on-box wrong-answer book and the
parent's enabled/disabled checkboxes keep pointing at the same slot) while:
  * replacing all 24 math items with <=20 addition/subtraction, including
    three-term sums within 10 (数圆点 / 比大小 items disappear),
  * randomising the correct-answer slot evenly across A/B/C/D,
  * renaming the 拼音 subject to 语文 (the pinyin/识字 items themselves stay).

Voice sentences are rebuilt from the exact same numbers shown on screen, so
the spoken question can never drift away from the text again.
"""
import os
import json
import random
from collections import Counter

BASE = os.path.dirname(os.path.abspath(__file__))
QS = os.path.join(BASE, "apk", "assets", "questions.json")
CN = "零一二三四五六七八九十"


def cn(n):
    if 0 <= n <= 10:
        return CN[n]
    if 10 < n < 20:
        return "十" + CN[n - 10]
    if n == 20:
        return "二十"
    return str(n)


def make_math(rng):
    """24 distinct items: 10-plus / 10-minus / chain sums / subtraction."""
    items = []

    def add(nums, op):
        a = nums[0]
        expr = (" + " if op == "+" else " - ").join(str(x) for x in nums)
        q = expr + " = ?"
        if op == "+":
            correct = sum(nums)
            say = "加".join(cn(x) for x in nums) + "等于几"
        else:
            correct = nums[0] - sum(nums[1:])
            say = "减".join(cn(x) for x in nums) + "等于几"
        items.append((q, correct, say))

    seen = set()

    # 1) 10 以内加法 (6)
    while len(items) < 6:
        a, b = rng.randint(1, 9), rng.randint(1, 9)
        if a + b > 10:
            continue
        t = (a, b, "+")
        if t in seen:
            continue
        seen.add(t)
        add(t[:2], "+")

    # 2) 10 以内连加, 三个加数 (5)
    chain = 0
    while chain < 5:
        a, b, c = rng.randint(1, 6), rng.randint(1, 6), rng.randint(1, 6)
        if a + b + c > 10:
            continue
        t = (a, b, c)
        if t in seen:
            continue
        seen.add(t)
        add([a, b, c], "+")
        chain += 1

    # 3) 20 以内加法 (6)
    plus20 = 0
    while plus20 < 6:
        a, b = rng.randint(2, 18), rng.randint(2, 18)
        total = a + b
        if not (11 <= total <= 20) or (a < 6 and b < 6):
            continue
        t = (a, b, "+")
        if t in seen:
            continue
        seen.add(t)
        add([a, b], "+")
        plus20 += 1

    # 4) 20 以内减法 (7)
    minus = 0
    while minus < 7:
        a = rng.randint(8, 20)
        b = rng.randint(1, a)
        t = (a, b, "-")
        if t in seen:
            continue
        seen.add(t)
        add([a, b], "-")
        minus += 1

    return items


def build_math(rng):
    raw = make_math(rng)
    # Even, but randomly ordered, correct-answer slots: the child cannot
    # memorise "the answer is always C".
    slots = [0, 1, 2, 3] * 6
    rng.shuffle(slots)
    out = []
    for (q, correct, say), slot in zip(raw, slots):
        near = [x for x in range(0, 21)
                if x != correct and abs(x - correct) <= 5]
        rng.shuffle(near)
        others = [str(x) for x in near[:3]]
        while len(others) < 3:
            v = str(rng.randint(0, 20))
            if v != str(correct) and v not in others:
                others.append(v)
        opts = others[:]
        opts.insert(slot, str(correct))
        voice = say + "。" + "。".join(
            "选项%s，%s" % (CN[i + 1], cn(int(o))) for i, o in enumerate(opts)) + "。"
        out.append({
            "subject": "数学", "q": q, "options": opts, "answer": slot,
            "voice": voice, "big": False, "colors": None, "title": None,
            "qcolor": None,
        })
    return out


def verify(items):
    """Every spoken sentence must contain the digits shown in the stem."""
    bad = []
    for it in items:
        nums = [int(x) for x in it["q"].split("=")[0].replace("-", " ")
                .replace("+", " ").split()]
        head = it["voice"].split("。")[0]
        for n in nums:
            if cn(n) not in head:
                bad.append((it["q"], n, head))
        opts = it["options"]
        tail = it["voice"]
        for i, o in enumerate(opts):
            if ("选项%s，%s" % (CN[i + 1], cn(int(o)))) not in tail:
                bad.append((it["q"], "opt%d" % i, tail[:60]))
        if not (0 <= it["answer"] < 4):
            bad.append((it["q"], "answer index", it["answer"]))
    return bad


def main():
    with open(QS, encoding="utf-8") as f:
        qs = json.load(f)["questions"]
    rng = random.Random(20261111)
    fresh = build_math(rng)

    math_ids = [q["id"] for q in qs if q["subject"] == "数学"]
    print("数学题位:", len(math_ids), "新题:", len(fresh))
    assert len(math_ids) == len(fresh) == 24

    errors = verify(fresh)
    if errors:
        raise SystemExit("题干/语音不一致: %s" % errors[:5])
    print("题干与语音一致性校验通过")

    by_id = dict(zip(math_ids, fresh))
    for q in qs:
        if q["id"] in by_id:
            new = dict(by_id[q["id"]])
            new["id"] = q["id"]          # never move a slot
            q.clear()
            q.update(new)
        elif q["subject"] == "拼音":
            q["subject"] = "语文"

    # Keep the JSON key order readable and stable across rebuilds.
    order = ("id", "subject", "q", "options", "answer", "voice", "big",
             "colors", "title", "qcolor", "image")
    for q in qs:
        ordered = {k: q[k] for k in order if k in q}
        for k in list(q):
            if k not in ordered:
                ordered[k] = q[k]
        q.clear()
        q.update(ordered)

    with open(QS, "w", encoding="utf-8") as f:
        json.dump({"questions": qs}, f, ensure_ascii=False, indent=1)

    print("科目分布:", dict(Counter(q["subject"] for q in qs)))
    print("数学答案位置分布:", dict(Counter(q["answer"] for q in qs
                                       if q["subject"] == "数学")))
    print("残留数圆点/比大小:", sum(1 for q in qs if "圆点" in q["q"]
                                or "更大" in q["q"]))
    print("数学样例:")
    for q in [x for x in qs if x["subject"] == "数学"][:6]:
        print("  ", q["id"], q["q"], q["options"], "->", q["answer"],
              "|", q["voice"][:52])


if __name__ == "__main__":
    main()
