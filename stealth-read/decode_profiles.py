#!/usr/bin/env python3
# decode_profiles.py —— 解 contact.db/chatter_profiles_v3 的 V3 profile blob。
# 逻辑移植自 app/.../ProfileBulk.java:顶层重复 field3=section(3.1=段标识,3.7=值JSON);
# 公司名=field1->field3->field1。值化简:部门(department_paths)/title/text/number。
import json, re, sys

SEC = {"B-DEPARTMENT":"department","B-ENTERPRISE-EMAIL":"email","B-JOBNUMBER":"employee_id",
       "B-JOB-TITLE":"position","B-LEADER":"leader","B-PHONE":"phone","B-CHATGROUPNICKNAME":"nickname"}

def varint(b, pos):
    r=0; shift=0
    while pos < len(b):
        x=b[pos]; pos+=1
        r |= (x & 0x7f) << shift
        if not (x & 0x80): break
        shift += 7
    return r, pos

def sub_range(b, start, end, field):
    """[start,end) 内首个 field 号=field 的 wt2 字段 -> (off,len);无则 None"""
    i=start
    while i < end:
        key,i = varint(b,i); fn=key>>3; wt=key&7
        if wt==0: _,i = varint(b,i)
        elif wt==2:
            l,i = varint(b,i)
            if l<0 or i+l>end: return None
            if fn==field: return (i,l)
            i += l
        elif wt==5: i+=4
        elif wt==1: i+=8
        else: return None
    return None

def sub_str(b,start,end,field):
    r=sub_range(b,start,end,field)
    if not r: return None
    try: return b[r[0]:r[0]+r[1]].decode('utf-8')
    except: return None

def collect_sections(b):
    out={}; i=0; n=len(b)
    while i<n:
        key,i=varint(b,i); fn=key>>3; wt=key&7
        if wt==0: _,i=varint(b,i)
        elif wt==2:
            l,i=varint(b,i)
            if l<0 or i+l>n: break
            if fn==3:
                sid=sub_str(b,i,i+l,1); val=sub_str(b,i,i+l,7)
                if sid and val and sid not in out: out[sid]=val
            i+=l
        elif wt==5: i+=4
        elif wt==1: i+=8
        else: break
    return out

def company_name(b):
    f1=sub_range(b,0,len(b),1)
    if not f1: return None
    f13=sub_range(b,f1[0],f1[0]+f1[1],3)
    if not f13: return None
    return clean(sub_str(b,f13[0],f13[0]+f13[1],1))

BIDI=re.compile('[‎‏‪-‮⁦-⁩]')
def clean(s): return BIDI.sub('', s).strip() if s else ""

def dv(x): return x.get('default_val') if isinstance(x,dict) else None

def flat(js):
    if not js: return ""
    try: o=json.loads(js)
    except: return js
    if not isinstance(o,dict): return js
    if 'department_paths' in o:
        allp=[]
        for p in o['department_paths']:
            one=[]
            for nd in (p.get('department_nodes') or []):
                nm=dv(nd.get('department_name'))
                if nm: one.append(nm)
            if one: allp.append('/'.join(one))
        return clean(' ; '.join(allp))
    if 'title' in o:  return clean(dv(o.get('title')) or "")
    if 'text' in o:   return clean(dv(o.get('text')) or "")
    if 'number' in o: return clean(str(o.get('number') or ""))
    return js

def decode_blob(hexstr):
    b=bytes.fromhex(hexstr)
    sec=collect_sections(b)
    rec={v:flat(sec.get(k)) for k,v in SEC.items()}
    cn=company_name(b)
    if cn: rec['company']=cn
    return rec

if __name__=="__main__":
    raw=json.load(open(sys.argv[1] if len(sys.argv)>1 else 'roster_raw.json'))
    CO='<YOUR_TENANT_ID>'
    out=[]
    for p in raw['people']:
        if p.get('tenant_id')!=CO: continue
        rec={'id':p.get('id'),'name':p.get('name'),'en':p.get('en_us_name'),
             'is_resigned':p.get('is_resigned')=='1'}
        if p.get('profile_hex'):
            try: rec.update(decode_blob(p['profile_hex']))
            except Exception as e: rec['decode_err']=str(e)
        out.append(rec)
    json.dump({'company':len(out),'people':out},
              open('roster_full.json','w'),
              ensure_ascii=False,indent=1)
    # 统计
    withdept=sum(1 for p in out if p.get('department'))
    withmail=sum(1 for p in out if p.get('email'))
    withno=sum(1 for p in out if p.get('employee_id'))
    withph=sum(1 for p in out if p.get('phone'))
    print(f"公司 {len(out)} 人 | 部门 {withdept} | 邮箱 {withmail} | 工号 {withno} | 电话 {withph}")
    print("落盘 ~/feishu_company_roster_full.json\n样例(前6个有部门的在职者):")
    shown=0
    for p in out:
        if p.get('department') and not p['is_resigned'] and p.get('name'):
            print(f"  {p['name']:<6} | 部门={p.get('department')} | 邮箱={p.get('email')} | 工号={p.get('employee_id')} | 职务={p.get('position')} | 上级={p.get('leader')}")
            shown+=1
            if shown>=6: break
