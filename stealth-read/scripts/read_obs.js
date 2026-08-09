'use strict';
// 观察 v2：SSL_write 抓 marker + 统计 send/sendto/write 时机；命中用 send() 回传
function bt(ctx) {
  return Thread.backtrace(ctx, Backtracer.ACCURATE).map(function (a) {
    var m = Process.findModuleByAddress(a);
    return (m ? (m.name + '+0x' + a.sub(m.base).toString(16)) : ('0x' + a.toString(16)));
  }).slice(0, 16);
}
function hasMarker(s) { return /im\.|put_read|read_index|read_position|mark_read|read_receipt|unread/i.test(s); }

var stats = { ssl_write: 0, sendto: 0, send: 0, write: 0, hits: 0 };

['libttboringssl.so', 'libssl.so'].forEach(function (modname) {
  var mod = Process.findModuleByName(modname);
  if (!mod) { send({ ev: 'nomod', mod: modname }); return; }
  var sw = mod.findExportByName('SSL_write');
  if (!sw) { send({ ev: 'noexport', mod: modname }); return; }
  Interceptor.attach(sw, {
    onEnter: function (args) {
      stats.ssl_write++;
      try {
        var n = args[2].toInt32();
        if (n <= 0 || n > 131072) return;
        var bytes = new Uint8Array(Memory.readByteArray(args[1], Math.min(n, 1024)));
        var s = '';
        for (var i = 0; i < bytes.length; i++) { var c = bytes[i]; s += (c >= 32 && c < 127) ? String.fromCharCode(c) : '.'; }
        if (hasMarker(s)) { stats.hits++; send({ ev: 'hit', mod: modname, n: n, preview: s.slice(0, 500), bt: bt(this.context) }); }
      } catch (e) { send({ ev: 'err', e: '' + e }); }
    }
  });
  send({ ev: 'hooked', mod: modname, at: '' + sw });
});

// 出口时机计数（加密数据无 marker，仅看时机）
['sendto', 'send', 'write'].forEach(function (sym) {
  var p = Module.findExportByName('libc.so', sym);
  if (p) Interceptor.attach(p, { onEnter: function () { stats[sym]++; } });
});

send({ ev: 'ready' });
setInterval(function () { send({ ev: 'beat', stats: stats }); }, 8000);
