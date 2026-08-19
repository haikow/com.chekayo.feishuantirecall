'use strict'
// hunt4.js — SSL_write 出站地真相:
//   回执不走 frontier-send(已证). 改看真正离开设备的字节: hook libttboringssl!SSL_write,
//   只在【读窗口】(read_position 写库后 2.5s 内)记录出站写: 长度 + 头部字节 + liblark backtrace.
//   你 idle 10s 再做一次读, 窗口内的 SSL_write backtrace = 真实回执发送链.
function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
whenLark(function (lark) {
  var base = lark.base, lo = base, hi = base.add(0x6600000)
  send({ ev: 'hooked', base: base.toString() })
  var readUntil = 0

  function btOffsets(ctx) {
    var out = []
    try {
      var bt = Thread.backtrace(ctx, Backtracer.ACCURATE)
      for (var i = 0; i < bt.length && out.length < 14; i++) {
        var p = bt[i]
        if (p.compare(lo) >= 0 && p.compare(hi) < 0) out.push('0x' + p.sub(base).toString(16))
      }
    } catch (e) {}
    return out
  }

  // SSL_write 拦截
  var sw = setInterval(function () {
    var ssl_write = getExp('libttboringssl.so', 'SSL_write')
    if (!ssl_write) return
    clearInterval(sw)
    Interceptor.attach(ssl_write, {
      onEnter: function (a) {
        if (Date.now() >= readUntil) return
        var num = a[2].toInt32()
        var head = ''
        try { head = a[1].readByteArray(Math.min(num, 24)) } catch (e) {}
        var hex = ''
        if (head) { var u = new Uint8Array(head); for (var i = 0; i < u.length; i++) hex += ('0' + u[i].toString(16)).slice(-2) }
        send({ ev: 'SSLW', t: Date.now() % 100000, len: num, head: hex, bt: btOffsets(this.context) })
      }
    })
    send({ ev: 'sslw_hooked' })
  }, 200)

  // read 窗口标记
  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step')
    var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    if (step && sqlp) { clearInterval(st); installSqlite(step, sqlp) }
  }, 200)
  function installSqlite(step, sqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (!s) return
        if (s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) {
          readUntil = Date.now() + 2500
          send({ ev: 'READ', t: Date.now() % 100000 })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
