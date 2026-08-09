// probe8: 不再"读"send_success, 而是"屏蔽"它, 双机实测是否真能压掉已读回执.
// 风险: send_success 若为全局共享回调, 屏蔽后飞书可能发不出消息/卡住/闪退.
// 带 g_block 开关 + 计数, 先只 dry-run(记录不屏蔽), 确认命中节奏后再改 g_block=1.
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out8.log";
  // ↓↓↓ 想真屏蔽把这行改成 1, 想先观察就留 0 ↓↓↓
  const g_block = 0;
  let fh = null; try { fh = new File(OUT, "a"); } catch (e) {}
  function log(s){const l="[AAPRO8] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe8 start  g_block="+g_block+" ====");
  let m = Process.findModuleByName("liblark.so");
  if (!m) { log("no liblark base"); return; }
  const base = m.base; log("base="+base+"  send@"+base.add(OFF_SEND));
  let hits = 0;
  Interceptor.attach(base.add(OFF_SEND), {
    onEnter(args) {
      hits++;
      if (hits <= 40) log("send_success #"+hits+" tid="+this.threadId+" x0="+this.context.x0+" x1="+this.context.x1);
      if (g_block) { this._skip = true; }
    },
    onLeave(retval) {
      if (this._skip) {
        // 直接改返回值/短路: 让它像"没发/已处理"返回. 先试 retval=0.
        retval.replace(ptr(0));
      }
    }
  });
  log("hook 挂好. g_block=0 只记录; 确认命中后改 g_block=1 再存脚本, 然后双机测.");
})();
