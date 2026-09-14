#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VaultForge 首页三 Tab 改版 · 效果图（草案 v3：新增动态验证码展示）"""
import os, math
from PIL import Image, ImageDraw, ImageFont

S = 2
def px(v): return int(round(v * S))

W, H = px(1530), px(1180)
BG = "#0F1416"; PANEL = "#151B1E"; BEZEL = "#0A0E0F"
CARD = "#1E272B"; CARD2 = "#20292D"; BORDER = "#2A3539"
TEXT = "#E8F1EE"; TEXT2 = "#93A6A2"; TEXT3 = "#6C7E7A"
ACC = "#3BAE8B"; ACC2 = "#2E8E76"; OK = "#4CAF7D"; WARN = "#E0B34C"; WHITE = "#FFFFFF"

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

FP = None
for p in ["/sdcard/fonts/NotoSansCJK-Regular.ttc",
          "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"]:
    if os.path.exists(p):
        FP = p; break
assert FP, "no font found"

IDX = 0
if FP.endswith(".ttc"):
    for i in range(12):
        try:
            f = ImageFont.truetype(FP, 20, index=i)
            if "SC" in "".join(f.getname()):
                IDX = i; break
        except Exception:
            break

_c = {}
def F(size):
    if size not in _c:
        _c[size] = ImageFont.truetype(FP, px(size), index=IDX)
    return _c[size]

def T(x, y, s, size, fill=TEXT, bold=False, anchor="la"):
    d.text((px(x), px(y)), s, font=F(size), fill=fill, anchor=anchor,
           stroke_width=(2 if bold else 0), stroke_fill=fill)

def RR(x, y, w, h, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([px(x), px(y), px(x + w), px(y + h)], radius=px(r),
                        fill=fill, outline=outline, width=px(width) if width else 0)

def LINE(x1, y1, x2, y2, color, width=1):
    d.line([px(x1), px(y1), px(x2), px(y2)], fill=color, width=px(width))

def CIRC(cx, cy, r, fill=None, outline=None, width=2):
    d.ellipse([px(cx - r), px(cy - r), px(cx + r), px(cy + r)], fill=fill,
              outline=outline, width=px(width) if width else 0)

def mini_btn(bx, by, w, label):
    RR(bx, by, w, 26, 13, fill="#233029")
    T(bx + w / 2, by + 7, label, 11, "#9FD8C6", anchor="ma")

def icon_vault(cx, cy, color):
    RR(cx - 11, cy - 8.5, 22, 17, 4.5, outline=color, width=2)
    CIRC(cx, cy + 0.5, 3.2, outline=color, width=2)
    LINE(cx, cy - 8.5, cx, cy - 4, color, 2)

def icon_shield(cx, cy, color):
    pts = [(cx - 9, cy - 10), (cx + 9, cy - 10), (cx + 9, cy - 1), (cx, cy + 9), (cx - 9, cy - 1), (cx - 9, cy - 10)]
    d.line([(px(a), px(b)) for a, b in pts], fill=color, width=px(2), joint="curve")
    d.line([(px(cx - 4), px(cy - 2)), (px(cx - 1), px(cy + 2)), (px(cx + 5), px(cy - 6))],
           fill=color, width=px(2), joint="curve")

def icon_gear(cx, cy, color):
    CIRC(cx, cy, 9, outline=color, width=2)
    CIRC(cx, cy, 3, outline=color, width=2)
    for k in range(6):
        a = math.radians(k * 60)
        d.line([px(cx + math.cos(a) * 9), px(cy + math.sin(a) * 9),
                px(cx + math.cos(a) * 12.5), px(cy + math.sin(a) * 12.5)], fill=color, width=px(2))

def phone(x, y):
    RR(x, y, 430, 900, 44, fill=BEZEL, outline="#242E31", width=1.5)
    sx, sy, sw, sh = x + 12, y + 12, 406, 876
    RR(sx, sy, sw, sh, 34, fill=PANEL, outline="#232D30", width=1)
    T(sx + 24, sy + 16, "18:32", 11, TEXT3)
    T(sx + sw - 24, sy + 16, "5G  88%", 11, TEXT3, anchor="ra")
    return sx, sy, sw, sh

def navbar(sx, sy, sw, sh, sel):
    navy = sy + sh - 88
    RR(sx + 16, navy, sw - 32, 68, 26, fill="#1A2225", outline=BORDER, width=1)
    slots = ["秘钥仓", "Bitwarden", "设置"]
    for i, label in enumerate(slots):
        cx = sx + 16 + (sw - 32) * (i + 0.5) / 3
        s = (i == sel)
        col = ACC if s else TEXT3
        if s:
            RR(cx - 50, navy + 6, 100, 56, 18, fill="#1E2C27")
        cy = navy + 22
        if i == 0: icon_vault(cx, cy, col)
        elif i == 1: icon_shield(cx, cy, col)
        else: icon_gear(cx, cy, col)
        T(cx, navy + 40, label, 12, ACC if s else TEXT2, bold=s, anchor="ma")

# ===== ① 秘钥仓（原页面）=====
sx, sy, sw, sh = phone(60, 140)
T(sx + 24, sy + 50, "秘钥仓", 26, TEXT, bold=True)
CIRC(sx + sw - 92, sy + 61, 7, outline=TEXT2, width=2)
LINE(sx + sw - 87, sy + 66, sx + sw - 81, sy + 72, TEXT2, 2)
LINE(sx + sw - 48, sy + 56, sx + sw - 48, sy + 72, TEXT2, 2)
LINE(sx + sw - 56, sy + 64, sx + sw - 40, sy + 64, TEXT2, 2)
chips = ["全部", "文件", "SSH", "API", "登录"]
cx0 = sx + 24
for i, cname in enumerate(chips):
    tw = d.textlength(cname, font=F(12)) / S + 30
    selc = (i == 0)
    if selc:
        RR(cx0, sy + 100, tw, 32, 16, fill=ACC2)
    else:
        RR(cx0, sy + 100, tw, 32, 16, fill="#20292D", outline=BORDER, width=1)
    T(cx0 + tw / 2, sy + 108.5, cname, 12, WHITE if selc else TEXT2, anchor="ma")
    cx0 += tw + 8
cards = [("观星154", "SSH · 154.89.149.92", "26ms", OK),
         ("宝塔154", "SSH · 154.89.149.92:51908", "41ms", OK),
         ("DeepSeek API", "API · api.deepseek.com", "验证中", WARN)]
yy = sy + 152
for name, sub, right, dot in cards:
    RR(sx + 24, yy, sw - 48, 112, 16, fill=CARD, outline=BORDER, width=1)
    CIRC(sx + 48, yy + 34, 6.5, fill=dot)
    T(sx + 70, yy + 16, name, 18, TEXT, bold=True)
    T(sx + 70, yy + 48, sub, 12, TEXT2)
    T(sx + sw - 40, yy + 22, right, 12, OK if right.endswith("ms") else WARN, anchor="ra")
    T(sx + sw - 34, yy + 52, "›", 20, TEXT3, anchor="ra")
    yy += 124
navbar(sx, sy, sw, sh, 0)

# ===== ② Bitwarden 密码库（条目浏览 + 动态验证码）=====
sx, sy, sw, sh = phone(550, 140)
T(sx + 24, sy + 48, "Bitwarden 密码库", 23, TEXT, bold=True)
T(sx + 24, sy + 82, "已连接 · i@bdshjgg.com", 12, TEXT2)
T(sx + sw - 24, sy + 82, "42 条 · 3 个文件夹", 12, TEXT3, anchor="ra")
RR(sx + 24, sy + 104, sw - 48, 44, 14, fill=CARD2, outline=BORDER, width=1)
CIRC(sx + 48, sy + 126, 7, outline=TEXT3, width=2)
LINE(sx + 53, sy + 131, sx + 58, sy + 136, TEXT3, 2)
T(sx + 68, sy + 118, "搜索名称 / 用户名", 13, TEXT3)

yy = sy + 164
# 首条：展开显示动态验证码
RR(sx + 24, yy, sw - 48, 104, 14, fill=CARD, outline=BORDER, width=1)
T(sx + 42, yy + 11, "GitHub", 15, TEXT, bold=True)
T(sx + 42, yy + 36, "octocat · github.com", 11, TEXT2)
bx = sx + sw - 42
for bt, bw in [("复制", 42), ("显示", 42), ("验证码", 50)]:
    bx -= bw
    mini_btn(bx, yy + 19, bw, bt)
    bx -= 8
LINE(sx + 40, yy + 62, sx + sw - 40, yy + 62, BORDER, 1)
T(sx + 42, yy + 76, "动态验证码", 11, TEXT2)
T(sx + 130, yy + 70, "794 213", 19, ACC, bold=True)
CIRC(sx + sw - 66, yy + 80, 9, outline=ACC2, width=2)
d.arc([px(sx + sw - 75), px(yy + 71), px(sx + sw - 57), px(yy + 89)], start=-90, end=90, fill=ACC, width=px(2))
T(sx + sw - 44, yy + 75, "16s", 11, TEXT3)
yy += 116
# 其余条目
els = [("阿里云控制台", "root · console.aliyun.com", True),
       ("微信", "wxid_8f***2k", False),
       ("宝塔面板", "bt · 154.89.149.92:8888", False),
       ("网易邮箱", "user@163.com", False)]
for name, sub, has_totp in els:
    RR(sx + 24, yy, sw - 48, 64, 14, fill=CARD, outline=BORDER, width=1)
    T(sx + 42, yy + 11, name, 15, TEXT, bold=True)
    T(sx + 42, yy + 36, sub, 11, TEXT2)
    bx = sx + sw - 42
    if has_totp:
        for bt, bw in [("复制", 42), ("显示", 42), ("验证码", 50)]:
            bx -= bw
            mini_btn(bx, yy + 19, bw, bt)
            bx -= 8
    else:
        for bt in ["复制", "显示"]:
            bx -= 42
            mini_btn(bx, yy + 19, 42, bt)
            bx -= 8
    yy += 76
T(sx + sw / 2, sy + sh - 112, "拉取 / 更新入口：设置 → Bitwarden 密码库", 11, TEXT3, anchor="ma")
navbar(sx, sy, sw, sh, 1)

# ===== ③ 设置（新增拉取入口）=====
sx, sy, sw, sh = phone(1040, 140)
T(sx + 24, sy + 50, "设置", 26, TEXT, bold=True)

def section(y, title, rows):
    T(sx + 24, y, title, 12, TEXT3)
    y2 = y + 20
    RR(sx + 24, y2, sw - 48, 52 * len(rows), 16, fill=CARD, outline=BORDER, width=1)
    ry = y2
    for i, (lab, kind, val) in enumerate(rows):
        if i > 0:
            LINE(sx + 40, ry, sx + sw - 40, ry, BORDER, 1)
        T(sx + 44, ry + 16, lab, 15, TEXT)
        if kind == "switch":
            sxx = sx + sw - 44 - 44
            RR(sxx, ry + 14, 44, 24, 12, fill=ACC2)
            CIRC(sxx + 32, ry + 26, 9, fill=WHITE)
        else:
            T(sx + sw - 44, ry + 17, val, 13, TEXT2, anchor="ra")
        ry += 52
    return y2 + 52 * len(rows) + 20

# —— Bitwarden 密码库区块（拉取入口）——
yy = sy + 108
T(sx + 24, yy, "Bitwarden 密码库", 12, TEXT3)
y2 = yy + 20
RR(sx + 24, y2, sw - 48, 104, 16, fill=CARD, outline=BORDER, width=1)
T(sx + 44, y2 + 16, "服务器", 15, TEXT)
T(sx + sw - 44, y2 + 17, "bit.bdshjgg.com ›", 13, TEXT2, anchor="ra")
LINE(sx + 40, y2 + 52, sx + sw - 40, y2 + 52, BORDER, 1)
T(sx + 44, y2 + 68, "邮箱", 15, TEXT)
T(sx + sw - 44, y2 + 69, "i@bdshjgg.com ›", 13, TEXT2, anchor="ra")
by = y2 + 104 + 12
RR(sx + 24, by, sw - 48, 46, 14, fill=ACC2)
T(sx + sw / 2, by + 23, "拉取密码库", 15, WHITE, bold=True, anchor="mm")
T(sx + sw / 2, by + 54, "主密码仅本次输入，不保存", 11, TEXT3, anchor="ma")

yy = section(by + 82, "通用", [("主题", "t", "跟随系统 ›"), ("进入自动检测", "switch", "")])
yy = section(yy, "云端同步", [("服务器", "t", "vf.bdshjgg.com ›"), ("账号", "t", "未登录 ›")])
yy = section(yy, "数据", [("数据导出", "t", "›")])
yy = section(yy, "关于", [("版本", "t", "v0.4.0")])
navbar(sx, sy, sw, sh, 2)

# ===== 标题 / 图注 =====
T(765, 34, "VaultForge · 首页底部导航三 Tab 改版 · 效果图（草案 v3）", 26, TEXT, bold=True, anchor="ma")
T(765, 76, "① 秘钥仓（原有页面） ② Bitwarden 密码库（条目浏览 · 动态验证码） ③ 设置（新增拉取入口） ｜ 深色墨绿冷灰主题", 13, TEXT2, anchor="ma")
for i, lab in enumerate(["① 秘钥仓（原有页面）", "② Bitwarden 密码库（浏览 + 验证码）", "③ 设置（拉取入口）"]):
    cx = 60 + 490 * i + 215
    T(cx, 1066, lab, 17, "#C7D6D1", bold=True, anchor="ma")
T(765, 1102, "v3 新增：动态验证码（TOTP）｜条目可看 6 位码：30 秒自动刷新 · 点击复制 · 本地生成（不联网）", 13, TEXT3, anchor="ma")
T(765, 1132, "v2 变更保留：拉取入口在设置 ｜ 待确认整体布局与交互 —— 确认后开始开发。", 12, TEXT3, anchor="ma")

out1 = "/sdcard/Download/Operit/vaultforge/mockup_bottom_nav_v3.png"
out2 = "/sdcard/Download/mockup_bottom_nav_v3.png"
os.makedirs("/sdcard/Download/Operit/vaultforge", exist_ok=True)
img.save(out1)
img.save(out2)
print("DONE", out1, os.path.getsize(out1))
print("DONE", out2, os.path.getsize(out2))