// probe2: dump send_success 参数内存, 找要上报的 messageId 列表(int64 大数).
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out2.log";
  let fh = null; try { fh = new File(OUT, "a"); } catch (e) {}
  function log(s){ const l="[AAPRO2] "+s; console.log(l); try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){} }
  log("==== probe2 start ====");
  let base = Process.findModuleByName("liblark.so");
  base = base ? base.base : null;
  if (!base) { log("no base"); return; }
  log("base="+base);

  // 判断一个 u64 是否像飞书 message_id/chat_id (19位十进制, 约 7.6e18 量级 -> 0x6a..0x69 高字节)
  function looksId(v) {
    // v 是 NativePointer/UInt64; 飞书雪花id ~ 7.6e18 = 0x69..0x6a xxxxxxxx
    try {
      const hi = v.shr(56).and(0xff).toNumber();
      return hi >= 0x50 && hi <= 0x70;   // 高字节落在雪花id区间
    } catch(e){ return false; }
  }
  function dumpRegion(name, p, qwords) {
    if (p.isNull()) { log(name+"=NULL"); return; }
    let out = name+"="+p+"\n";
    for (let i = 0; i < qwords; i++) {
      let a = p.add(i*8);
      let v; try { v = a.readU64(); } catch(e){ out += "  ["+i+"] <unreadable>\n"; continue; }
      let vp = ptr(v);
      let tag = looksId(vp) ? "  <== 像ID" : "";
      out += "  ["+(i)+"] +0x"+(i*8).toString(16)+" = 0x"+v.toString(16)+tag+"\n";
    }
    log(out);
  }

  let hits=0;
  Interceptor.attach(base.add(OFF_SEND), {
    onEnter(args) {
      hits++;
      if (hits > 3) return;   // 只 dump 前 3 次, 避免刷屏
      log("#### 命中 #"+hits+" tid="+this.threadId+"  x3="+this.context.x3+" ####");
      dumpRegion("x0", this.context.x0, 24);
      dumpRegion("x1", this.context.x1, 24);
    }
  });
  log("hook 挂好, 请开未读聊天");
})();
