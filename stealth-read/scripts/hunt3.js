'use strict'
// hunt3.js — neuter 已读回执:
//   Interceptor.replace sub_5AED220 (read_message/logic.rs 的已读上报函数) -> 直接 return 0, 不发送.
//   返回值不被调用方使用(0x59dc4c8 立刻丢弃 X0), 本地 read_position 写库是独立路径 -> 本地已读/红点不受影响.
//   同时保留 READ 标记日志, 确认本地读仍触发; 计数被拦截次数.
var OFF_RECEIPT = 0x5aed220

function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked', base: base.toString() })

  var blocked = 0
  var target = base.add(OFF_RECEIPT)
  Interceptor.replace(target, new NativeCallback(function (a1, a2) {
    blocked++
    send({ ev: 'BLOCKED_RECEIPT', n: blocked, a2: a2, t: Date.now() % 100000 })
    return 0
  }, 'pointer', ['pointer', 'int']))
  send({ ev: 'receipt_neutered', off: '0x' + OFF_RECEIPT.toString(16) })

  // 保留 read 本地写库标记, 确认本地读仍发生
  function getExp(modname, sym) {
    var m = Process.findModuleByName(modname); if (!m) return null
    try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
  }
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
          send({ ev: 'READ_LOCAL', t: Date.now() % 100000, sql: s.substring(0, 50) })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
