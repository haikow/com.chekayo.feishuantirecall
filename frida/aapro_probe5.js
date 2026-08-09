// probe5(无Stalker,安全): dump send_success x1 里 (ptr,len) 对指向的缓冲, 看是否 messageId 列表.
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out5.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[AAPRO5] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe5 start ====");
  let m=Process.findModuleByName("liblark.so"); if(!m){log("no base");return;}
  const base=m.base; log("base="+base);
  function looksId(v){try{const hi=v.shr(56).and(0xff).toNumber();return hi>=0x50&&hi<=0x72;}catch(e){return false;}}

  // 把 x1 当结构, 扫所有 (ptr,len) 对: 某槽是堆指针、下一槽是小整数(1..256), 就 dump 那段 buffer
  function dumpVecs(x1){
    for(let i=0;i<40;i++){
      let pv,lv;
      try{ pv=x1.add(i*8).readU64(); lv=x1.add((i+1)*8).readU64(); }catch(e){break;}
      let pp=ptr(pv);
      let hi=pp.shr(40).toNumber();
      let len=ptr(lv).toNumber ? Number(lv):0;
      // pp 像用户堆指针, len 是 1..64 的合理长度
      if(hi>=0x77 && hi<=0x79 && lv>0n && lv<=64n){
        let arr=[]; let idCnt=0;
        for(let j=0;j<Number(lv) && j<32;j++){
          let v; try{v=pp.add(j*8).readU64();}catch(e){break;}
          let isid=looksId(ptr(v)); if(isid)idCnt++;
          arr.push("0x"+v.toString(16)+(isid?"*":""));
        }
        if(idCnt>=1) log("  x1+0x"+(i*8).toString(16)+" -> Vec(ptr="+pp+",len="+Number(lv)+") 元素[*=像ID]: "+arr.join(" "));
      }
    }
  }
  let hits=0;
  Interceptor.attach(base.add(OFF_SEND),{
    onEnter(){ hits++; if(hits>3)return;
      log("#### send #"+hits+" tid="+this.threadId+" ####");
      let x1=this.context.x1, x0=this.context.x0;
      log("  扫 x1="+x1); dumpVecs(x1);
      log("  扫 x0="+x0); dumpVecs(x0);
    }
  });
  log("hook 挂好, 请开未读聊天");
})();
