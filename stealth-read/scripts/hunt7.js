'use strict'
// hunt7.js — 判定 A: 掐断【全部】frontier 发送, 看对方是否还已读.
//   no-op sub_649FE5C (frontier 终端入队). 其调用者 sub_64A0364 无视返回值且照常唤醒执行器 -> 安全.
//   若读后对方变未读 => 回执走 frontier; 若仍已读 => 彻底排除 frontier(转 HTTP/cronet).
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
  var base = lark.base
  send({ ev: 'hooked', base: base.toString() })
  var dropped = 0
  Interceptor.replace(base.add(OFF_ENQ), new NativeCallback(function (a1, a2, a3, a4) {
    dropped++
    if (dropped <= 5 || dropped % 50 === 0) send({ ev: 'FRONTIER_DROP', n: dropped, a3: a3, t: Date.now() % 100000 })
    return ptr(0)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'frontier_killed', off: '0x' + OFF_ENQ.toString(16) })

  // read 标记(确认读发生)
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
        if (s && s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) send({ ev: 'READ', t: Date.now() % 100000 })
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
