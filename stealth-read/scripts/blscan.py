# blscan.py - arm64 静态 BL 调用方扫描器 (7.69.6 liblark)
# 用法: python3 blscan.py 0x5aec998 0x5b36a88 ...  -> 打印每个目标的所有 bl 调用点
# 离线、毫秒级, 比 frida read_xref.js 快; 注意: blr/vtable 间接调用扫不到(async future 即此类).
# .text vaddr/off/size 已按 7.69.6 写死; 换版本需改 TEXT_* 三个常量.
import struct, sys
import numpy as np

SO = "./liblark-arm64.so"
# .text: vaddr 0x2fd4940, file off 0x2fd0940, size 0x3626938
TEXT_VADDR = 0x2fd4940
TEXT_OFF   = 0x2fd0940
TEXT_SIZE  = 0x3626938

with open(SO,"rb") as f:
    f.seek(TEXT_OFF); data = f.read(TEXT_SIZE)
n = len(data)//4
u = np.frombuffer(data[:n*4], dtype="<u4")
pcs = TEXT_VADDR + 4*np.arange(n, dtype=np.int64)

def callers_of(target):
    # BL: bits[31:26]=100101 -> 0x94000000 mask 0xFC000000
    is_bl = (u & 0xFC000000) == 0x94000000
    imm26 = (u & 0x03FFFFFF).astype(np.int64)
    imm26 = np.where(imm26 & 0x02000000, imm26 - 0x04000000, imm26)
    tgt = pcs + (imm26<<2)
    hit = is_bl & (tgt == target)
    return pcs[hit]

targets = [int(x,16) for x in sys.argv[1:]]
for t in targets:
    cs = callers_of(t)
    print("== callers of 0x%x : %d ==" % (t, len(cs)))
    for c in cs[:40]:
        print("   0x%x" % c)
