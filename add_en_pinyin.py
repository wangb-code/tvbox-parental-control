# -*- coding: utf-8 -*-
"""英语『看单词选词义』题：中文选项改为纯带调拼音（孩子只认拼音不认汉字）。
语音文本不动 —— 语音仍读中文词发音，孩子听音对照屏幕拼音即可作答。
"""
import io, json

# 26 个词义词的规范读音（轻声手工校对）
CN_PINYIN = {
    "苹果": "píngguǒ", "香蕉": "xiāngjiāo", "猫": "māo", "狗": "gǒu",
    "鸡蛋": "jīdàn", "鱼": "yú", "山羊": "shānyáng", "帽子": "màozi",
    "冰": "bīng", "果汁": "guǒzhī", "风筝": "fēngzheng", "狮子": "shīzi",
    "牛奶": "niúnǎi", "鼻子": "bízi", "橙子": "chéngzi", "猪": "zhū",
    "女王": "nǚwáng", "兔子": "tùzi", "太阳": "tàiyáng", "老虎": "lǎohǔ",
    "雨伞": "yǔsǎn", "货车": "huòchē", "水": "shuǐ", "盒子": "hézi",
    "溜溜球": "liūliūqiú", "斑马": "bānmǎ",
}

def to_py(s):
    """中文词 -> 带调拼音；英文单词原样。"""
    return CN_PINYIN[s] if s in CN_PINYIN else s

def main():
    p = "apk/assets/questions.json"
    data = json.load(io.open(p, encoding="utf-8"))
    qs = data["questions"]
    changed = 0
    for q in qs:
        if q["subject"] != "英语":
            continue
        before = q["options"][q["answer"]]
        q["options"] = [to_py(o) for o in q["options"]]
        for v in q["variants"]:
            v["options"] = [to_py(o) for o in v["options"]]
        # 「苹果」的英文是？ -> 题干中文词改拼音，孩子才看得懂题意
        for zh, py in CN_PINYIN.items():
            if "「%s」" % zh in q["q"]:
                q["q"] = q["q"].replace("「%s」" % zh, "「%s」" % py)
                break
        if q["options"][q["answer"]] != before:
            changed += 1

    # 校验：变体答案拼音须等于原题正确拼音；每项互不相同
    errs = []
    for q in qs:
        if q["subject"] != "英语":
            continue
        base = q["options"][q["answer"]]
        for v in q["variants"]:
            if v["options"][v["answer"]] != base:
                errs.append(q["id"] + "-ans")
            if len(v["options"]) != 4 or len(set(v["options"])) != 4:
                errs.append(q["id"] + "-dup")
    if errs:
        raise SystemExit("校验失败: " + str(errs[:8]))

    json.dump(data, io.open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("英语题选项改为纯拼音，修改题数:", changed)
    for q in [x for x in qs if x["subject"] == "英语"][:4]:
        print(" ", q["q"], "->", q["options"], "| 语音:", q["variants"][0]["voice"][:45])

if __name__ == "__main__":
    main()
