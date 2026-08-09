'use strict'
// hunt9.js — 时间门控丢弃: 只在【读检测后窗口内】丢 frontier 发送, 放过 chat-open 拉消息.
//   触发: sqlite "UPDATE chats SET read_position" -> 开 800ms 丢弃窗口.
//   sub_649FE5C 返回值不被调用方使用 -> 丢弃(return 0)安全. 验证: 对方未读 + chat 能开 + 能收发.
var OFF_ENQ = 0x649fe5c
var WINDOW_MS = 800
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
  var dropUntil = 0, dropped = 0
  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    if (Date.now() < dropUntil) {
      dropped++
      send({ ev: 'DROP', n: dropped, a3: a3 & 0xff, t: Date.now() % 100000 })
      return ptr(0)
    }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'gated_drop_armed', win: WINDOW_MS })

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
        if (s && s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) {
          dropUntil = Date.now() + WINDOW_MS
          send({ ev: 'READ_WINDOW_OPEN', t: Date.now() % 100000 })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
