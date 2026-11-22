"""Replace pinyin syllable options in 拼一拼 questions with the homophone
characters already spoken in each voice track.

The voice says e.g. 选项一，波。选项二，拉。... so using those exact characters
keeps screen and audio aligned without re-synthesising any mp3. Variants have
their own permuted voice tracks, so each variant is converted from its OWN
voice text, preserving the option/answer position mapping.
"""
import json
import re
import os

P = os.path.join(os.path.dirname(__file__), "apk", "assets", "questions.json")
PAT = re.compile(r"选项([一二三四])，([^。]+)。")
IDX = {"一": 0, "二": 1, "三": 2, "四": 3}


def spoken_options(voice):
    hits = PAT.findall(voice)
    if len(hits) != 4:
        raise ValueError("expected 4 spoken options, got %d: %s" % (len(hits), voice))
    words = {}
    for cn, word in hits:
        i = IDX[cn]
        if i in words:
            raise ValueError("duplicate option %s" % cn)
        words[i] = word.strip()
    return [words[i] for i in range(4)]


def main():
    with open(P, encoding="utf-8") as f:
        data = json.load(f)
    qs = data["questions"]
    changed = []
    for q in qs:
        if "拼一拼" not in q.get("q", ""):
            continue
        qid = q["id"]
        targets = [(q, q["voice"], "main")]
        for v in q.get("variants", []):
            targets.append((v, v["voice"], v.get("voice_id", "?")))
        if len(targets) != 5:
            raise ValueError("%s has %d voice tracks (expected 5)" % (qid, len(targets)))
        for obj, voice, tag in targets:
            words = spoken_options(voice)
            old = list(obj["options"])
            obj["options"] = words
            # Verify against the recorded answer too.
            ai = obj["answer"]
            if not (0 <= ai < 4):
                raise ValueError("%s/%s bad answer index %s" % (qid, tag, ai))
        changed.append((qid, q["q"], q["options"], q["options"][q["answer"]]))

    # Full verification: every track's screen options == words spoken in that track.
    for q in qs:
        if "拼一拼" not in q.get("q", ""):
            continue
        for obj, voice, tag in [(q, q["voice"], "main")] + [
                (v, v["voice"], v.get("voice_id", "?")) for v in q.get("variants", [])]:
            if obj["options"] != spoken_options(voice):
                raise AssertionError("mismatch %s/%s" % (q["id"], tag))
            if len(obj["options"]) != 4 or any(not x for x in obj["options"]):
                raise AssertionError("bad options %s/%s" % (q["id"], tag))

    with open(P, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)

    print("改写拼读题 %d 道（含各 4 套变体，共 %d 组选项）" % (len(changed), len(changed) * 5))
    for qid, q, opts, ans in changed:
        print("  %-5s %-18s %s -> 答案「%s」" % (qid, q, "/".join(opts), ans))
    print("全部语音与选项一致性校验通过（无需重录 mp3）")


if __name__ == "__main__":
    main()
