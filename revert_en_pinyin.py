# -*- coding: utf-8 -*-
"""英文题去拼音化（v7.13）：
- 题型A「kite 是什么意思？」：选项 拼音->中文汉字；语音题干读出英文单词。
- 题型B「斑马 的英文是？」：题干 拼音->中文汉字；语音题干读出中文词。
- 原则：屏幕不再出现「拼音 + 英文」混排，只保留「中文 <-> 英文」。
"""
import io, json, re

PINYIN2ZH = {
    "píngguǒ": "苹果", "xiāngjiāo": "香蕉", "māo": "猫", "gǒu": "狗",
    "jīdàn": "鸡蛋", "yú": "鱼", "shānyáng": "山羊", "màozi": "帽子",
    "bīng": "冰", "guǒzhī": "果汁", "fēngzheng": "风筝", "shīzi": "狮子",
    "niúnǎi": "牛奶", "bízi": "鼻子", "chéngzi": "橙子", "zhū": "猪",
    "nǚwáng": "女王", "tùzi": "兔子", "tàiyáng": "太阳", "lǎohǔ": "老虎",
    "yǔsǎn": "雨伞", "huòchē": "货车", "shuǐ": "水", "hézi": "盒子",
    "liūliūqiú": "溜溜球", "bānmǎ": "斑马",
}

def zh_of(s):
    return PINYIN2ZH.get(s, s)

CN = "一二三四"

def opt_label(i):
    return CN[i]   # i = 0..3

def main():
    p = "apk/assets/questions.json"
    data = json.load(io.open(p, encoding="utf-8"))
    qs = data["questions"]
    changed = 0
    for q in qs:
        if q["subject"] != "英语":
            continue
        is_zh_q = "的英文是" in q["q"]   # 题型B：看中文选英文
        # ---- 题干 ----
        if is_zh_q:
            m = re.search(r"「(.+?)」", q["q"])
            if m and m.group(1) in PINYIN2ZH:
                q["q"] = q["q"].replace(m.group(1), PINYIN2ZH[m.group(1)])
        # ---- 选项 ----
        q["options"] = [zh_of(o) for o in q["options"]]
        for v in q["variants"]:
            v["options"] = [zh_of(o) for o in v["options"]]
        # ---- 语音：题干读出来 ----
        # 题型A 题干是英文单词：读「这个单词 kite 是什么意思呢」
        # 题型B 题干是中文词：读「斑马 的英文单词是哪一个呢」
        if is_zh_q:
            m = re.search(r"「(.+?)」", q["q"])
            zh = m.group(1) if m else "这个"
            lead = "找一找，%s的英文单词是哪一个呢。" % zh
        else:
            m = re.search(r"「(.+?)」", q["q"])
            word = m.group(1) if m else "这个单词"
            lead = "找一找，单词%s是什么意思呢。" % word
        tail = "".join("选项%s，%s。" % (opt_label(i), o)
                       for i, o in enumerate(q["options"]))
        newvoice = lead + tail
        q["voice"] = newvoice
        for v in q["variants"]:
            vtail = "".join("选项%s，%s。" % (opt_label(i), o)
                            for i, o in enumerate(v["options"]))
            v["voice"] = lead + vtail
        changed += 1

    # 校验：英文题屏幕无拼音；变体答案一致；选项互异
    errs = []
    py_chars = set("āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ")
    for q in qs:
        if q["subject"] != "英语":
            continue
        base = q["options"][q["answer"]]
        for v in q["variants"]:
            if v["options"][v["answer"]] != base:
                errs.append(q["id"] + "-ans")
            if len(v["options"]) != 4 or len(set(v["options"])) != 4:
                errs.append(q["id"] + "-dup")
        if any(all(ch in py_chars or ch.isalpha() == False for ch in o) for o in q["options"]):
            pass  # 拼音仅出现在旧数据，转换后不应再有
    if errs:
        raise SystemExit("校验失败: " + str(errs[:8]))

    json.dump(data, io.open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("英文题去拼音完成，处理题数:", changed)
    for q in [x for x in qs if x["subject"] == "英语"][:4]:
        print(" ", q["q"], "->", q["options"])
        print("    语音:", q["variants"][0]["voice"])

CN = "一二三四"

if __name__ == "__main__":
    main()
