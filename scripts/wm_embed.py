#!/usr/bin/env python3
# LSB 隐写: 把所有权标记嵌入 PNG 的 RGB 低位。全图重复冗余 + 魔术头, 抗裁剪/轻改。
# 用法: wm_embed.py <in.png> <out.png> "<marker text>"
import sys, struct
from PIL import Image

MAGIC = b'FLW1'   # fuck lark watermark v1

def main():
    inp, outp, text = sys.argv[1], sys.argv[2], sys.argv[3]
    payload = text.encode('utf-8')
    frame = MAGIC + struct.pack('>H', len(payload)) + payload   # 头(4)+长度(2)+载荷
    bits = []
    for byte in frame:
        for i in range(7, -1, -1):
            bits.append((byte >> i) & 1)

    img = Image.open(inp).convert('RGBA')
    w, h = img.size
    px = bytearray(img.tobytes())        # RGBA 连续字节
    cap = 0                              # 可用 LSB 数 = 像素数 * 3(RGB, 跳过 A)
    n = 0
    L = len(bits)
    for i in range(0, len(px), 4):       # 每像素 4 字节 RGBA
        for ch in range(3):              # 只用 R,G,B
            px[i+ch] = (px[i+ch] & 0xFE) | bits[n % L]
            n += 1
    out = Image.frombytes('RGBA', (w, h), bytes(px))
    out.save(outp, 'PNG')
    print("embedded %d bytes payload, repeated %d times across %d LSBs" % (len(payload), n // L, n))

if __name__ == '__main__':
    main()
