// probe7: 裸 dump x1+0x98/0xa8/0xb8 指向的三个缓冲(不做id过滤), 看到底装什么.
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out7.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[AAPRO7] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe7 start ====");
  let m=Process.findModuleByName("liblark.so"); if(!m){log("no base");return;}
  const base=m.base; log("base="+base);
  function u64(p){try{return p.readU64();}catch(e){return null;}}
  function rawdump(label,pp,n){
    if(pp.isNull()){log("  "+label+"=NULL");return;}
    let a=[]; for(let j=0;j<n;j++){let v=u64(pp.add(j*8)); a.push("0x"+(v?v.toString(16):"?"));}
    log("  "+label+" @"+pp+":\n    "+a.join(" "));
    // 也看首元素若是指针, 下探它(可能是 message 结构, 含 id)
    let f=u64(pp); if(f){let fp=ptr(f); let h=parseInt(fp.shr(40).toString());
      if(h>=0x77&&h<=0x79){ let b=[]; for(let j=0;j<12;j++){let v=u64(fp.add(j*8)); b.push("0x"+(v?v.toString(16):"?"));} log("    首元素->"+fp+": "+b.join(" ")); }}
  }
  let hits=0;
  Interceptor.attach(base.add(OFF_SEND),{
    onEnter(){ hits++; if(hits>2)return;
      let x1=this.context.x1;
      log("#### send #"+hits+" tid="+this.threadId+" x1="+x1+" ####");
      let p98=u64(x1.add(0x98)), pa8=u64(x1.add(0xa8)), pb8=u64(x1.add(0xb8));
      if(p98) rawdump("buf@+0x98(len9)", ptr(p98), 12);
      if(pa8) rawdump("buf@+0xa8(len9)", ptr(pa8), 12);
      if(pb8) rawdump("buf@+0xb8(len3)", ptr(pb8), 8);
    }
  });
  log("hook 挂好, 请开未读聊天");
})();
