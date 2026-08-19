// 算法助手Pro 进程内 frida-gum。输出同时 console.log + 追加写 /data/data/com.ss.android.lark/aapro_out.log(便于 adb 读取)。
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out.log";
  let fh = null;
  try { fh = new File(OUT, "a"); } catch (e) {}
  function log(s) {
    const line = "[AAPRO] " + s;
    console.log(line);
    try { if (fh) { fh.write(line + "\n"); fh.flush(); } } catch (e) {}
  }
  log("==== script start " + new Date().toString() + " ====");

  function larkBase() {
    let m = Process.findModuleByName("liblark.so");
    if (m) { log("findModuleByName OK base=" + m.base); return m.base; }
    log("findModuleByName=null, 读 /proc/self/maps 兜底");
    try {
      const lines = File.readAllText("/proc/self/maps").split("\n");
      for (const line of lines) {
        if (line.indexOf("liblark.so") >= 0) {
          const p = line.split(/\s+/);
          if (p[1] && p[1][2] === 'x' && p[2] === "00000000")
            return ptr("0x" + line.split("-")[0]);
        }
      }
    } catch (e) { log("maps读取失败: " + e); }
    return null;
  }

  const base = larkBase();
  log("liblark base = " + base);
  if (!base) { log("拿不到基址, 停"); return; }
  const send = base.add(OFF_SEND);
  log("send_success @ " + send);
  let hits = 0;
  try {
    Interceptor.attach(send, {
      onEnter(args) {
        hits++;
        if (hits > 10) return;
        log("==== send_success 命中 #" + hits + " tid=" + this.threadId + " ====");
        let regs = [];
        for (let i = 0; i < 6; i++) regs.push("x"+i+"="+this.context["x"+i]);
        log("  " + regs.join(" "));
        const bt = Thread.backtrace(this.context, Backtracer.FUZZY)
          .map(a => (a.sub(base).compare(0) > 0 && a.sub(base).compare(0x9000000) < 0)
                    ? ("liblark+0x" + a.sub(base).toString(16)) : a.toString());
        log("  FUZZY bt:\n    " + bt.slice(0,24).join("\n    "));
      }
    });
    log("hook 已挂 ✓  请打开一个【有未读】的聊天");
  } catch (e) { log("attach 失败: " + e); }
})();
