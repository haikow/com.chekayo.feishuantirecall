'use strict'
// hunt8.js (轻量版) — 只读 frontier 统一入队 sub_649FE5C, 读 descriptor 对象头部找 vtable 区分命令.
//   无 backtrace(避免热路径拖垮). desc=a4[2]; 读 desc 头两个 qword(可能含静态 vtable/类型指针).
var OFF_ENQ = 0x649fe5c
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
whenLark(function (lark) {
  var base = lark.base, lo = base, hi = base.add(0x6600000)
  send({ ev: 'hooked', base: base.toString() })
  function off(p) { if (!p || p.isNull()) return '0'; return (p.compare(lo) >= 0 && p.compare(hi) < 0) ? '0x' + p.sub(base).toString(16) : ('ext') }
  Interceptor.attach(base.add(OFF_ENQ), {
    onEnter: function (a) {
      var desc = null, q0 = null, q1 = null
      try { desc = a[3].add(16).readPointer() } catch (e) {}
      if (desc && !desc.isNull()) { try { q0 = desc.readPointer() } catch (e) {} try { q1 = desc.add(8).readPointer() } catch (e) {} }
      var a3 = a[2].toInt32() & 0xff
      send({ ev: 'SEND', t: Date.now() % 100000, a3: a3, desc: off(desc), q0: off(q0), q1: off(q1) })
    }
  })
  send({ ev: 'enq_hooked' })

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
        if (s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) send({ ev: 'READ_POS', t: Date.now() % 100000 })
        else if (s.indexOf('UPDATE') >= 0 && s.indexOf('me_read') >= 0) send({ ev: 'ME_READ', t: Date.now() % 100000 })
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
