// probe6: 修正类型; 原样 dump x1[0..0x110], 对每个堆指针后跟小整数的槽 dump 其缓冲找 messageId.
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out6.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[AAPRO6] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe6 start ====");
  let m=Process.findModuleByName("liblark.so"); if(!m){log("no base");return;}
  const base=m.base; log("base="+base);
  function u64(p){ try{return p.readU64();}catch(e){return null;} }
  function toN(v){ try{return parseInt(v.toString());}catch(e){return -1;} }
  function isHeap(p){ try{let h=parseInt(p.shr(40).toString());return h>=0x77&&h<=0x79;}catch(e){return false;} }
  function looksId(v){ try{let hi=parseInt(v.shr(56).and(0xff).toString());return hi>=0x50&&hi<=0x72;}catch(e){return false;} }

  function probe(name, x){
    if(x.isNull())return;
    // 原样打印 0x80..0x108
    let raw=[];
    for(let i=0x10;i<0x22;i++){ let v=u64(x.add(i*8)); raw.push("+0x"+(i*8).toString(16)+"=0x"+(v?v.toString(16):"?")); }
    log("  "+name+" 区段: "+raw.join(" "));
    // 扫 (堆指针, 小整数) 对
    for(let i=0;i<0x20;i++){
      let pv=u64(x.add(i*8)); if(!pv)continue; let pp=ptr(pv);
      if(!isHeap(pp))continue;
      let lv=u64(x.add((i+1)*8)); if(!lv)continue; let len=toN(lv);
      if(len<1||len>64)continue;
      let arr=[],idc=0;
      for(let j=0;j<len&&j<24;j++){ let v=u64(pp.add(j*8)); if(!v){break;} let id=looksId(v); if(id)idc++; arr.push("0x"+v.toString(16)+(id?"*":"")); }
      if(idc>=1) log("    "+name+"+0x"+(i*8).toString(16)+" -> (ptr="+pp+",len="+len+"): "+arr.join(" "));
    }
  }
  let hits=0;
  Interceptor.attach(base.add(OFF_SEND),{
    onEnter(){ hits++; if(hits>3)return;
      log("#### send #"+hits+" tid="+this.threadId+" ####");
      probe("x0",this.context.x0); probe("x1",this.context.x1);
    }
  });
  log("hook 挂好, 请开未读聊天");
})();
