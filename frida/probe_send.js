// 从 send_success(sub_5B9A318 @ liblark+0x5B9A318) 起, FUZZY 栈回溯找运行时读发送链
const OFF_SEND = 0x5B9A318;

function larkBase() {
  // 反 frida 可能让 Process.findModuleByName 返回 null -> 读 /proc/self/maps 兜底
  let m = Process.findModuleByName("liblark.so");
  if (m) return m.base;
  const fp = new File("/proc/self/maps", "r");
  let line, base = null;
  while ((line = fp.readLine())) {
    if (line.indexOf("liblark.so") >= 0 && line.indexOf(" r-xp ") >= 0) {
      // 取该 so 第一个映射(offset 0)的起始
      const start = line.split("-")[0];
      // 需要 offset=0 的段作为 ELF 基址; r-xp offset 0 段即基址(本 so 首段 r-xp off 0)
      const parts = line.split(/\s+/);
      if (parts[2] === "00000000") { base = ptr("0x" + start); break; }
    }
  }
  fp.close();
  return base;
}

const base = larkBase();
console.log("[*] liblark base = " + base);
if (!base) { console.log("[!] 找不到 liblark 基址"); }
else {
  const send = base.add(OFF_SEND);
  console.log("[*] send_success @ " + send);
  let hits = 0;
  Interceptor.attach(send, {
    onEnter(args) {
      hits++;
      if (hits > 12) return;
      console.log("\n[SEND #" + hits + "] send_success 命中");
      // FUZZY 栈扫描(不依赖 CFI, 穿 async rust 安全)
      const bt = Thread.backtrace(this.context, Backtracer.FUZZY)
        .map(a => {
          const off = a.sub(base);
          return "liblark+0x" + off.toString(16);
        });
      console.log("  fuzzy bt: " + bt.slice(0, 20).join("\n            "));
    }
  });
  console.log("[*] hook 已挂, 请打开有未读的聊天...");
}
