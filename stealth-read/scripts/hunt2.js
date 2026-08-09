'use strict'
// hunt2.js — 关联 hunt(无 Stalker, 稳定):
//   hook sub_59AFFEC(通用 frontier 发送), 每次记 descriptor 偏移 + caller 偏移 + 时间;
//   hook sqlite3_step 命中 "UPDATE chats SET ... read_position/last_message_position" 时打 READ 标记.
//   你 idle 5s 再做【一次】读, 看 READ 之后紧跟哪个 descriptor = 已读回执候选.
var OFF_FRONTSEND = 0x59affec

function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
whenLark(function (lark) {
  var base = lark.base, lo = base, hi = base.add(0x6600000)
  send({ ev: 'hooked', base: base.toString() })

  // 1) frontier send 关联
  Interceptor.attach(base.add(OFF_FRONTSEND), {
    onEnter: function (a) {
      var desc = a[2]
      var dOff = '?'
      if (desc && !desc.isNull() && desc.compare(lo) >= 0 && desc.compare(hi) < 0) dOff = '0x' + desc.sub(base).toString(16)
      var ret = this.returnAddress
      var cOff = (ret && ret.compare(lo) >= 0 && ret.compare(hi) < 0) ? '0x' + ret.sub(base).toString(16) : '?'
      send({ ev: 'SEND', t: Date.now() % 100000, desc: dOff, caller: cOff })
    }
  })

  // 2) read 标记
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
        if (s.indexOf('UPDATE') >= 0 && (s.indexOf('read_position') >= 0 || s.indexOf('last_message_position') >= 0)) {
          send({ ev: 'READ', t: Date.now() % 100000, sql: s.substring(0, 60) })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
