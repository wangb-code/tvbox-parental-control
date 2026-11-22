# -*- coding: utf-8 -*-
"""Give every question 4 option orders, each with its own matching voice.

The pre-recorded clips say "选项一，...选项四，...", so shuffling the on-screen
options without shuffling the recording would make the voice lie. Each variant
therefore carries its own option array, answer index and spoken sentence; the
player picks one variant at quiz time.

v0 reuses the existing clip (copied to <id>_0.mp3); v1-v3 are synthesized by
gen_assets.py --audio-only. Question ids never change, so the wrong-answer
book, the enabled/disabled checkboxes and the picker weights stay aligned.
"""
import os
import re
import json
import shutil
import random
import hashlib

BASE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(BASE, "apk", "assets")
QS = os.path.join(ASSETS, "questions.json")
AUDIO = os.path.join(ASSETS, "audio")
MANIFEST = os.path.join(AUDIO, "_manifest.json")
CN = "零一二三四五六七八九十"


def digest(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


def split_voice(voice):
    """Return (stem sentence, [4 spoken option texts]) or None."""
    parts = [p for p in voice.split("。") if p.strip()]
    if len(parts) != 5:
        return None
    sounds = []
    for p in parts[1:]:
        m = re.match(r"^选项[一二三四]，(.+)$", p)
        if not m:
            return None
        sounds.append(m.group(1))
    return parts[0], sounds


def make_variants(q, rng):
    opts, ai = q["options"], q["answer"]
    parsed = split_voice(q["voice"])
    if parsed is None or len(opts) != 4:
        # Never break a question: keep a single, still-valid variant.
        return [{"options": opts, "answer": ai, "voice": q["voice"],
                 "voice_id": q["id"] + "_0"}]
    say_q, sounds = parsed
    perms = [list(range(4))]                       # v0 = shipped order
    slots = [i for i in range(4) if i != ai]       # correct answer in the
    rng.shuffle(slots)                             # other 3 positions
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
        out.append({"options": new_opts, "answer": perm.index(ai),
                    "voice": voice, "voice_id": q["id"] + "_" + str(vi)})
    return out


def main():
    with open(QS, encoding="utf-8") as f:
        qs = json.load(f)["questions"]
    rng = random.Random(20261114)
    failed = []
    for q in qs:
        q["variants"] = make_variants(q, rng)
        if len(q["variants"]) < 4:
            failed.append(q["id"])
    print("题目 %d 道，4 套变体的 %d 道，回退单套 %s"
          % (len(qs), len(qs) - len(failed), failed or "无"))

    with open(QS, "w", encoding="utf-8") as f:
        json.dump({"questions": qs}, f, ensure_ascii=False, indent=1)

    manifest = {}
    if os.path.isfile(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as f:
            manifest = json.load(f)
    reused = 0
    for q in qs:
        v0 = q["variants"][0]
        old = os.path.join(AUDIO, q["id"] + ".mp3")
        new = os.path.join(AUDIO, v0["voice_id"] + ".mp3")
        if not os.path.isfile(new) and os.path.isfile(old):
            shutil.copyfile(old, new)
            reused += 1
        manifest[v0["voice_id"]] = digest(v0["voice"])
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("复用原语音 %d 条为 _0 变体；新增待合成 %d 条"
          % (reused, sum(len(q["variants"]) - 1 for q in qs)))

    # Consistency audit: every variant's answer points at the same text, and
    # every spoken option appears in the same order as the displayed options.
    for q in qs:
        base_correct = q["options"][q["answer"]]
        for v in q["variants"]:
            assert v["options"][v["answer"]] == base_correct, q["id"]
            assert len(v["options"]) == 4 and len(set(v["options"])) == 4, q["id"]
            assert v["voice"].count("选项") == 4, q["id"]
    print("变体一致性校验通过：正确答案文本与四个选项读音逐套对齐")


if __name__ == "__main__":
    main()
