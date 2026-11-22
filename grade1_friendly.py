# -*- coding: utf-8 -*-
"""一年级难度选项字库：常用汉字，按带调拼音分组。

来源：pinyin_map 的同音字 + 部编版一年级高频字，剔除明显生僻字。
每个字用 pypinyin 复核带调读音，确保题目"带调拼音 -> 选对应汉字"准确。
"""
from pypinyin import pinyin, Style

# 明确不适合一年级的生僻字（从 pinyin_map 候选中剔除）
HARD = set("醭婀簸跛厕厦遮诅疵蘑拇渠奢淤髓叵轧尬嘚忒讷勒努摸鹅"
           "蜈蟀耙撮捌剌嗄咯呃噢喔"

           )

# 一年级常用字（含 pinyin_map 常用字 + 课本高频字）
POOL = """
八拔把爸 妈麻马骂 大答打搭 哥格哥个 湖虎户呼 西洗习细
炉鲁路 坡婆破 哪拿那 一姨椅意 屋无五雾 鱼雨玉 米谜蜜
低底弟递 踢题体替 兔土图突 婆泼 鼻笔币 皮匹屁 批
的得 打 太抬台 八 发法伐 夫斧父福 姑古故 哭苦裤 姑固
鸡急几记 七骑起气 居举句 区取去 须许序 知直纸只 猪竹主住
吃迟尺赤 出厨楚处 师十史是 书熟数树 沙啥傻 蛇舍社
资子字 租足组 粗促 丝死四 苏俗速
压牙哑亚 爷也业 衣已椅意 淤鱼雨玉
挖娃瓦袜 窝我握 屋无五雾 饿鹅恶
波拨播 摸磨墨木母 佛 得 特
南那拿 梨利力 路鲁 绿旅
"""
CHARS = []
for ch in POOL:
    if '\u4e00' <= ch <= '\u9fff' and ch not in CHARS and ch not in HARD:
        CHARS.append(ch)


def py(ch):
    try:
        return pinyin(ch, style=Style.TONE)[0][0]
    except Exception:
        return None


def build_map():
    """带调拼音 -> [一年级常用字]，读音以 pypinyin 复核。"""
    m = {}
    for ch in CHARS:
        p = py(ch)
        if not p or p in HARD:
            continue
        m.setdefault(p, []).append(ch)
    return m


if __name__ == "__main__":
    m = build_map()
    print("字库字数:", len(CHARS), "覆盖带调拼音:", len(m))
    for p in sorted(m)[:30]:
        print("  ", p, "->", m[p])
