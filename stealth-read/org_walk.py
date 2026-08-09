#!/usr/bin/env python3
# org_walk.py —— 飞书组织架构 DFS 自动巡游器(配合算法助手注入的 aapro_roster.js)
#
# 思路:飞书组织树是懒加载——进哪个部门才拉哪个部门的成员进 contact.db。
# 本脚本用 adb+uiautomator 递归进入每个部门、滑到底,逼飞书把全公司成员加载出来,
# 注入在飞书进程里的 aapro_roster.js 随即就地 dump 累积。
#
# 行区分:有「（N）」人数尾巴=部门(点进去递归);无尾巴=人(跳过,不点)。
# 用法:先确保飞书前台在【通讯录→组织内联系人→公司根】,再跑本脚本。
import subprocess, re, time, sys, json, os

ROSTER = "/data/data/com.ss.android.lark/files/aapro_roster.json"
MAXDEPTH = 6
_last_dump = ""

def sh(*a):
    return subprocess.run(["adb","shell",*a], capture_output=True, text=True).stdout

def su(cmd):
    return subprocess.run(["adb","shell","su","-c",cmd], capture_output=True, text=True).stdout

def tap(x,y): sh("input","tap",str(int(x)),str(int(y)))
def back():   sh("input","keyevent","4")
def swipe(x1,y1,x2,y2,ms=350): sh("input","swipe",str(x1),str(y1),str(x2),str(y2),str(ms))

def dump_rows():
    """返回 [(title, has_count, cx, cy)], 以及面包屑末节点文本。"""
    sh("uiautomator","dump","/sdcard/w.xml")
    xml = sh("cat","/sdcard/w.xml")
    titles=[]; tails=[]; crumbs=[]
    for m in re.finditer(r'<node[^>]*?/?>', xml):
        s=m.group(0)
        def g(k):
            mm=re.search(k+r'="([^"]*)"', s); return mm.group(1) if mm else ''
        rid=g('resource-id').split('/')[-1]; txt=g('text'); bnd=g('bounds')
        b=re.findall(r'\d+', bnd)
        if len(b)<4: continue
        x1,y1,x2,y2=map(int,b[:4]); cy=(y1+y2)//2; cx=(x1+x2)//2
        if rid=='avatar_item_title' and txt: titles.append((txt,cx,cy))
        elif rid=='avatar_item_title_tail' and txt: tails.append(cy)
        elif rid=='breadcrumb_label_tv' and txt: crumbs.append(txt)
    rows=[]
    for (txt,cx,cy) in titles:
        has_count = any(abs(cy-ty)<40 for ty in tails)
        rows.append((txt,has_count,cx,cy))
    crumb = crumbs[-1] if crumbs else ''
    return rows, crumb

def scroll_down_changed():
    """向上滑一屏(看更多),返回是否有变化(靠 dump 文本差异判断到底)。"""
    global _last_dump
    swipe(620,2200,620,800,300); time.sleep(0.8)
    cur = sh("cat","/sdcard/w.xml")
    changed = (cur != _last_dump)
    _last_dump = cur
    return changed

def scroll_top():
    for _ in range(8):
        swipe(620,800,620,2300,250)
    time.sleep(0.5)

def roster_count():
    txt = su(f"cat {ROSTER} 2>/dev/null")
    try: return json.loads(txt).get("total",0)
    except: return -1

def scan_depts():
    """滑到底,收集本页所有子部门名(顺序去重),同时触发成员懒加载。"""
    global _last_dump
    names=[]; _last_dump=""
    scroll_top()
    for _ in range(40):
        rows,_=dump_rows()
        for (txt,hc,cx,cy) in rows:
            if hc and txt not in names: names.append(txt)
        if not scroll_down_changed(): break
    return names

def tap_dept(name):
    """从顶部找到该部门并点入。"""
    global _last_dump
    _last_dump=""
    scroll_top()
    for _ in range(40):
        rows,_=dump_rows()
        for (txt,hc,cx,cy) in rows:
            if txt==name and hc:
                tap(cx,cy); time.sleep(1.5); return True
        if not scroll_down_changed(): return False
    return False

VISITED_FILE="/tmp/org_walk_visited.json"
try: visited=set(json.load(open(VISITED_FILE)))
except: visited=set()
def save_visited():
    try: json.dump(sorted(visited), open(VISITED_FILE,"w"), ensure_ascii=False)
    except: pass
def walk(path):
    depth=len(path)
    if depth>=MAXDEPTH: return
    depts=scan_depts()
    print(f"{'  '*depth}[{'/'.join(path) or 'ROOT'}] 子部门={depts}  roster={roster_count()}", flush=True)
    for name in depts:
        key='/'.join(path+[name])
        if key in visited:
            print(f"{'  '*depth}  ↷跳过已完成 {name}", flush=True); continue
        if tap_dept(name):
            walk(path+[name])
            visited.add(key); save_visited()   # 子树全走完才标记(支持断点续跑)
            back(); time.sleep(1.2)
        else:
            print(f"{'  '*depth}  !未能进入 {name}", flush=True)

if __name__=="__main__":
    print("起始 roster:", roster_count(), flush=True)
    t0=time.time()
    walk([])
    print(f"\n巡游完成,用时 {int(time.time()-t0)}s,最终 roster={roster_count()}", flush=True)
