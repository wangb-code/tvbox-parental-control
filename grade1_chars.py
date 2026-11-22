# -*- coding: utf-8 -*-
"""部编版小学一年级（上+下）常用识字表，用于生成一年级难度的选项汉字。

用 pypinyin 自动标注带调拼音，只保留读音明确（单拼音、声母+单韵母
或常用复韵母）的字，作为语文拼音题的候选选项字。
"""
from pypinyin import pinyin, Style

# 部编版一年级上册 + 下册识字表（去重复、常见字）
GRADE1 = """
一 二 三 四 五 六 七 八 九 十 零
人 口 手 足 耳 目 牙 眉 心 头 发 面
日 月 星 天 空 云 风 雨 雪 电 火 水 山 石 田 土 地 河 海 江
木 林 森 树 叶 花 草 苗 米 果 瓜 豆 禾 竹 桥 船
爸 妈 爷 奶 哥 姐 弟 妹 叔 姑 你 我 他 她 它 们
大 小 多 少 长 短 高 矮 胖 瘦 上 下 左 右 前 后 里 外 中
天 地 人 你 我 他 一 二 三 四 五 六 七 八 九 十
春 夏 秋 冬 早 晚 午 晨 年 时 分 今 明 昨 那 这 会 个
白 黑 红 黄 绿 蓝 紫 灰 彩 色 光 影 亮 暗
牛 马 羊 猪 狗 猫 鸡 鸭 鹅 鸟 鱼 虫 兔 猴 虎 象 熊 蛙
手 足 走 跑 跳 站 坐 立 飞 游 爬 说 话 看 听 读 写 画 唱
吃 喝 玩 笑 哭 睡 醒 起 床 洗 刷 穿 脱 开 关 门 窗
book 中 文 语 数 学 音 乐 美 体 课 本 字 词 句 文 章 故 事
朋 友 同 学 老 师 工 人 农 民 医 生 警 察 司 机 阿 姨
家 庭 房 屋 楼 门 窗 床 桌 椅 灯 钟 车 路 桥 店 园 校
东 西 南 北 方 向 边 角 间 处 地 场
有 无 是 非 对 错 好 坏 新 旧 真 假 美 丑 香 臭 甜 苦 酸 辣
喜 欢 爱 怕 想 要 能 会 可 以 不 没 也 很 太 最 更 再 还 都
和 与 或 但 因 为 所 以 如 果 那 么
"""
# 去掉非汉字 token
_tokens = GRADE1.split()
GRADE1_CHARS = []
for t in _tokens:
    for ch in t:
        if '\u4e00' <= ch <= '\u9fff' and ch not in GRADE1_CHARS:
            GRADE1_CHARS.append(ch)


def py_of(ch):
    """取单字标准拼音（带调），多音字取第一种读音。"""
    try:
        arr = pinyin(ch, style=Style.TONE, heteronym=False)
        return arr[0][0] if arr else None
    except Exception:
        return None


def build_map():
    """带调拼音 -> [一年级常用字]（取 pypinyin 标注，多音字仅取首音）。"""
    m = {}
    for ch in GRADE1_CHARS:
        p = py_of(ch)
        if not p:
            continue
        m.setdefault(p, []).append(ch)
    return m


if __name__ == "__main__":
    m = build_map()
    print("一年级字表字数:", len(GRADE1_CHARS))
    print("覆盖带调拼音:", len(m))
    # 检查当前60题正确拼音是否都有对应一年级字
    import json, re
    qs = json.load(open("apk/assets/questions.json", encoding="utf-8"))["questions"]
    pys = [re.search(r"「(.+?)」", q["q"]).group(1) for q in qs
           if q["subject"] == "语文" and re.search(r"「(.+?)」", q["q"])]
    have = [p for p in set(pys) if p in m]
    lack = [p for p in set(pys) if p not in m]
    print("当前题拼音有一年级字:", len(have), "缺字:", sorted(lack))
