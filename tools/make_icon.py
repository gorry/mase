"""mase のアイコン（アダプティブアイコンの前景）を作る。ベクターの XML と、確認用の PNG を出す。
使い方（リポジトリの直下で）: python tools/make_icon.py app/src/main/res <確認用の PNG の出力先> [pic]
3 つ目にフォルダー（pic）を付けると、アプリの外で使う mase_icon.svg と mase_icon_1024.png も、そこに出す。
確認用の PNG を作るのに、Pillow が要る。"""
import math, sys
from PIL import Image, ImageDraw

KEY = '#7FFFFF'      # キーカラー
BG = '#10181C'       # 背景（暗い色）
BLADE = '#FFFFFF'    # カッターの刃

# ---- 図案（108x108 の中。見える範囲は中央の直径 66 の円の中に収める） ----
SX0, SY0, SX1, SY1 = 30.0, 29.0, 76.0, 61.0     # 画面（角の丸い四角）
RADIUS = 4.0
MID = (SY0 + SY1) / 2
AMPS = [3, 7, 12, 6, 13, 9, 4, 10, 13, 7, 4, 2]  # 半周期ごとの振幅（ばらばらに変わる）
WX0, WX1 = SX0 + 5, SX1 - 5
HALF = (WX1 - WX0) / len(AMPS)

def wave_path():
    d = ['M%.2f,%.2f' % (WX0, MID)]
    for i, a in enumerate(AMPS):
        x0 = WX0 + HALF * i
        sign = -1 if i % 2 == 0 else 1
        # 2 次ベジェの山の高さは、制御点のずれの半分
        d.append('Q%.2f,%.2f %.2f,%.2f' % (x0 + HALF / 2, MID + sign * 2 * a, x0 + HALF, MID))
    return ' '.join(d)

def wave_points(n=12):
    pts = []
    for i, a in enumerate(AMPS):
        x0 = WX0 + HALF * i
        sign = -1 if i % 2 == 0 else 1
        cx, cy = x0 + HALF / 2, MID + sign * 2 * a
        for k in range(n + (1 if i == len(AMPS) - 1 else 0)):
            t = k / n
            x = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * (x0 + HALF)
            y = (1 - t) ** 2 * MID + 2 * (1 - t) * t * cy + t * t * MID
            pts.append((x, y))
    return pts

# カッター：刃先を原点、上向きに描いて、左上を向くように 45 度回し、刃先を TIP に置く
TIP = (57.0, 55.0)
ANGLE = -45.0
BLADE_PTS = [(-3.2, 0.0), (3.2, 5.5), (3.2, 10.0), (-3.2, 10.0)]
BODY = (-4.6, 10.0, 4.6, 29.0)       # 持ち手（角の丸い四角）
BODY_R = 2.0
KNOB = (-1.6, 15.0, 1.6, 21.0)       # スライダー（持ち手の上の小さな四角。背景色で抜く）
KNOB_R = 1.2

def tf(p):
    a = math.radians(ANGLE)
    x, y = p
    return (TIP[0] + x * math.cos(a) - y * math.sin(a), TIP[1] + x * math.sin(a) + y * math.cos(a))

def rrect_path(x0, y0, x1, y1, r):
    return ('M%.2f,%.2f H%.2f A%.2f,%.2f 0 0 1 %.2f,%.2f V%.2f A%.2f,%.2f 0 0 1 %.2f,%.2f '
            'H%.2f A%.2f,%.2f 0 0 1 %.2f,%.2f V%.2f A%.2f,%.2f 0 0 1 %.2f,%.2f Z') % (
        x0 + r, y0, x1 - r, r, r, x1, y0 + r, y1 - r, r, r, x1 - r, y1,
        x0 + r, r, r, x0, y1 - r, y0 + r, r, r, x0 + r, y0)

def poly_path(pts):
    return 'M' + ' L'.join('%.2f,%.2f' % p for p in pts) + ' Z'

def write_xml(res):
    import os
    os.makedirs(os.path.join(res, 'drawable'), exist_ok=True)
    os.makedirs(os.path.join(res, 'mipmap-anydpi-v26'), exist_ok=True)
    fg = '''<?xml version="1.0" encoding="utf-8"?>
<!-- アプリのアイコンの前景。振幅がばらばらに変わるサイン波の画面と、切り出しを表すカッター。
     図案は memo/doc/spec.md の「アプリのアイコン」。このファイルは tools/make_icon.py が作る。図案を変えるときは、そちらを直して作り直す -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- 画面 -->
    <path
        android:pathData="%s"
        android:strokeColor="%s"
        android:strokeWidth="2.6" />
    <!-- サイン波 -->
    <path
        android:pathData="%s"
        android:strokeColor="%s"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeWidth="2" />
    <!-- カッター（刃先を画面の中に置く。まわりを背景色でふちどって、画面の線と分ける） -->
    <group
        android:rotation="%.1f"
        android:translateX="%.1f"
        android:translateY="%.1f">
        <path
            android:fillColor="%s"
            android:pathData="%s"
            android:strokeColor="%s"
            android:strokeLineJoin="round"
            android:strokeWidth="2.4" />
        <path
            android:fillColor="%s"
            android:pathData="%s"
            android:strokeColor="%s"
            android:strokeWidth="2.4" />
        <path
            android:fillColor="%s"
            android:pathData="%s" />
        <path
            android:fillColor="%s"
            android:pathData="%s" />
        <path
            android:fillColor="%s"
            android:pathData="%s" />
    </group>
</vector>
''' % (rrect_path(SX0, SY0, SX1, SY1, RADIUS), KEY, wave_path(), KEY, ANGLE, TIP[0], TIP[1],
       BG, poly_path(BLADE_PTS), BG, BG, rrect_path(*BODY, BODY_R), BG,
       BLADE, poly_path(BLADE_PTS), KEY, rrect_path(*BODY, BODY_R), BG, rrect_path(*KNOB, KNOB_R))
    open(os.path.join(res, 'drawable', 'ic_launcher_foreground.xml'), 'w', encoding='utf-8', newline='\n').write(fg)
    open(os.path.join(res, 'mipmap-anydpi-v26', 'ic_launcher.xml'), 'w', encoding='utf-8', newline='\n').write('''<?xml version="1.0" encoding="utf-8"?>
<!-- アプリのアイコン（アダプティブアイコン）。背景は 1 色、前景はベクター -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
''')
    open(os.path.join(res, 'values', 'ic_launcher_colors.xml'), 'w', encoding='utf-8', newline='\n').write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- アプリのアイコンの背景の色 -->
    <color name="ic_launcher_background">%s</color>
</resources>
''' % BG)

def rrect_poly(x0, y0, x1, y1, r, n=8):
    pts = []
    for cx, cy, a0 in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for k in range(n + 1):
            a = math.radians(a0 + 90 * k / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts

def preview(path):
    K = 8
    im = Image.new('RGB', (108 * K, 108 * K), BG)
    d = ImageDraw.Draw(im)
    s = lambda pts: [(x * K, y * K) for x, y in pts]
    scr = rrect_poly(SX0, SY0, SX1, SY1, RADIUS)
    d.line(s(scr + [scr[0]]), fill=KEY, width=int(2.6 * K), joint='curve')
    d.line(s(wave_points()), fill=KEY, width=2 * K, joint='curve')
    blade = [tf(p) for p in BLADE_PTS]
    body = [tf(p) for p in rrect_poly(*BODY, BODY_R)]
    knob = [tf(p) for p in rrect_poly(*KNOB, KNOB_R)]
    d.line(s(blade + [blade[0]]), fill=BG, width=int(2.4 * K), joint='curve'); d.polygon(s(blade), fill=BG)
    d.line(s(body + [body[0]]), fill=BG, width=int(2.4 * K), joint='curve')
    d.polygon(s(blade), fill=BLADE); d.polygon(s(body), fill=KEY); d.polygon(s(knob), fill=BG)
    im = im.resize((432, 432), Image.LANCZOS)
    # 並べて見る：そのまま、円で切り抜いたもの（直径 72）、角の丸い四角で切り抜いたもの、小さくしたもの
    out = Image.new('RGB', (432 * 3 + 40, 432), '#404040')
    out.paste(im, (0, 0))
    for i, kind in enumerate(('circle', 'squircle')):
        mask = Image.new('L', (432, 432), 0); md = ImageDraw.Draw(mask)
        box = (18 * 4, 18 * 4, 90 * 4, 90 * 4)
        if kind == 'circle': md.ellipse(box, fill=255)
        else: md.rounded_rectangle(box, radius=64, fill=255)
        out.paste(im, (432 * (i + 1) + 20 * (i + 1), 0), mask)
    small = im.crop((72, 72, 360, 360)).resize((48, 48), Image.LANCZOS)
    out.paste(small, (432 * 2 + 40 - 60, 432 - 60))
    out.save(path)

# ---- アプリの外で使う画像（SVG と PNG） ----
# アイコンとして見える範囲（108x108 の中央の 72x72）を、正方形のまま出す。角は丸めない
VIEW0, VIEW1 = 18.0, 90.0

def write_svg(path):
    svg = '''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="%g %g %g %g" width="1024" height="1024">
  <rect x="%g" y="%g" width="%g" height="%g" fill="%s"/>
  <path d="%s" fill="none" stroke="%s" stroke-width="2.6"/>
  <path d="%s" fill="none" stroke="%s" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
  <g transform="translate(%g %g) rotate(%g)">
    <path d="%s" fill="%s" stroke="%s" stroke-width="2.4" stroke-linejoin="round"/>
    <path d="%s" fill="%s" stroke="%s" stroke-width="2.4"/>
    <path d="%s" fill="%s"/>
    <path d="%s" fill="%s"/>
    <path d="%s" fill="%s"/>
  </g>
</svg>
''' % (VIEW0, VIEW0, VIEW1 - VIEW0, VIEW1 - VIEW0, VIEW0, VIEW0, VIEW1 - VIEW0, VIEW1 - VIEW0, BG,
       rrect_path(SX0, SY0, SX1, SY1, RADIUS), KEY, wave_path(), KEY, TIP[0], TIP[1], ANGLE,
       poly_path(BLADE_PTS), BG, BG, rrect_path(*BODY, BODY_R), BG, BG,
       poly_path(BLADE_PTS), BLADE, rrect_path(*BODY, BODY_R), KEY, rrect_path(*KNOB, KNOB_R), BG)
    open(path, 'w', encoding='utf-8', newline='\n').write(svg)

def draw_icon(K):
    """108x108 の図を、1 単位あたり K ピクセルで描く"""
    im = Image.new('RGB', (108 * K, 108 * K), BG)
    d = ImageDraw.Draw(im)
    s = lambda pts: [(x * K, y * K) for x, y in pts]
    def stroke(pts, color, width, closed=True):
        # 線は、線の上に細かく並べた円で描く（折れ線として描くと、つなぎ目に欠けが出る）
        if closed:
            pts = pts + [pts[0]]
        r = width * K / 2
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            n = max(1, int(math.hypot(x1 - x0, y1 - y0) / 0.03))
            for k in range(n + 1):
                x = (x0 + (x1 - x0) * k / n) * K
                y = (y0 + (y1 - y0) * k / n) * K
                d.ellipse((x - r, y - r, x + r, y + r), fill=color)
    def round_ends(pts, color, width):
        pass  # 円を並べて描くので、端は、はじめから丸い
    stroke(rrect_poly(SX0, SY0, SX1, SY1, RADIUS), KEY, 2.6)
    wave = wave_points(24)
    stroke(wave, KEY, 2, closed=False); round_ends(wave, KEY, 2)
    blade = [tf(p) for p in BLADE_PTS]
    body = [tf(p) for p in rrect_poly(*BODY, BODY_R)]
    knob = [tf(p) for p in rrect_poly(*KNOB, KNOB_R)]
    stroke(blade, BG, 2.4); d.polygon(s(blade), fill=BG)
    stroke(body, BG, 2.4)
    d.polygon(s(blade), fill=BLADE); d.polygon(s(body), fill=KEY); d.polygon(s(knob), fill=BG)
    return im

def write_png(path, size=1024):
    K = 64  # 大きく描いてから縮めて、線のふちをなめらかにする
    im = draw_icon(K).crop((int(VIEW0 * K), int(VIEW0 * K), int(VIEW1 * K), int(VIEW1 * K)))
    im.resize((size, size), Image.LANCZOS).save(path)

if __name__ == '__main__':
    write_xml(sys.argv[1]); preview(sys.argv[2])
    if len(sys.argv) > 3:
        # 3 つ目の引数（フォルダー）があれば、アプリの外で使う SVG と 1024x1024 の PNG も出す
        import os
        write_svg(os.path.join(sys.argv[3], 'mase_icon.svg'))
        write_png(os.path.join(sys.argv[3], 'mase_icon_1024.png'))
    print('ok')
