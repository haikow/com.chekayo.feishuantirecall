# xref_data.py - arm64 adrp+add 数据 xref 扫描器 (7.69.6 liblark)
# 用法: python3 xref_data.py 0xLO 0xHI   -> 打印所有解析到 [LO,HI) 的 adrp+add 代码点+目标
# 找"谁引用了某 .rodata 字符串/数据", 补 IDA 全局 xref. .text 常量按 7.69.6 写死.
import sys, numpy as np
SO="./liblark-arm64.so"
TV,TO,TS=0x2fd4940,0x2fd0940,0x3626938
d=open(SO,"rb").read()[TO:TO+TS]
n=len(d)//4
u=np.frombuffer(d[:n*4],dtype="<u4")
pc=TV+4*np.arange(n,dtype=np.int64)
# adrp
isadrp=(u & 0x9F000000)==0x90000000
immlo=((u>>29)&3).astype(np.int64)
immhi=((u>>5)&0x7FFFF).astype(np.int64)
imm21=(immhi<<2)|immlo
imm21=np.where(imm21&0x100000, imm21-0x200000, imm21)
tpage=(pc & ~np.int64(0xfff)) + (imm21<<12)
rd=(u&0x1F)
# add imm (next)
nx=np.empty_like(u); nx[:-1]=u[1:]; nx[-1]=0
isadd=(nx & 0xFF800000)==0x91000000
addrn=((nx>>5)&0x1F)
addimm=((nx>>10)&0xFFF).astype(np.int64)
addsh=((nx>>22)&3)
addimm=np.where(addsh==1, addimm<<12, addimm)
pair=isadrp & isadd & (addrn==rd)
resolved=tpage+addimm
LO=int(sys.argv[1],16); HI=int(sys.argv[2],16)
hit=pair & (resolved>=LO) & (resolved<HI)
idx=np.nonzero(hit)[0]
print(f"== adrp+add resolving into [0x{LO:x},0x{HI:x}) : {len(idx)} ==")
for i in idx[:60]:
    print(f"  code=0x{int(pc[i]):x}  -> data=0x{int(resolved[i]):x}")
