#!/usr/bin/env python3
# 从 PNG 提取隐写水印(取证)。扫描所有魔术头出现处, 多数表决还原载荷。
# 用法: wm_extract.py <in.png>
import sys, struct
from collections import Counter
from PIL import Image

MAGIC = b'FLW1'

def main():
    img = Image.open(sys.argv[1]).convert('RGBA')
    px = img.tobytes()
    # 收集 RGB LSB 成 bit 流
    bits = bytearray()
    for i in range(0, len(px), 4):
        for ch in range(3):
            bits.append(px[i+ch] & 1)
    # 打包成字节流
    b = bytearray()
    for i in range(0, len(bits) - 7, 8):
        v = 0
        for j in range(8):
            v = (v << 1) | bits[i+j]
        b.append(v)
    data = bytes(b)
    # 在所有字节对齐偏移里找魔术头(载荷帧从任意 bit 开始, 但我们连续嵌, 帧长固定, 多处对齐)
    found = []
    idx = 0
    while True:
        p = data.find(MAGIC, idx)
        if p < 0: break
        try:
            ln = struct.unpack('>H', data[p+4:p+6])[0]
            payload = data[p+6:p+6+ln].decode('utf-8')
            if 0 < ln <= 4096:
                found.append(payload)
        except Exception:
            pass
        idx = p + 1
    if not found:
        print("未检出水印(FLW1)。可能非本作者原图, 或图片被重编码/有损压缩破坏了 LSB。")
        sys.exit(2)
    top, cnt = Counter(found).most_common(1)[0]
    print("检出水印 (命中 %d 处, 多数表决):\n%s" % (len(found), top))

if __name__ == '__main__':
    main()
