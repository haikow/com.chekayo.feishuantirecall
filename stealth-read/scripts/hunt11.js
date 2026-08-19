'use strict'
// hunt11.js — 诊断: 丢回执的同时, 监控 feed_channel.new_message_count 时间线, 验证"服务器回推红点"假设.
//   gated drop(hunt9) + 记录每次 feed_channel 写的 new_message_count 真实值 + 是否在丢弃窗口内.
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
    if (Date.now() < dropUntil) { dropped++; return ptr(0) }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'gated_drop_armed' })

  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step')
    var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    var esqlp = getExp('libsqlcipher.so', 'sqlite3_expanded_sql')
    if (step && sqlp) { clearInterval(st); install(step, sqlp, esqlp) }
  }, 200)
  function install(step, sqlp, esqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
    var esqlf = esqlp ? new NativeFunction(esqlp, 'pointer', ['pointer']) : null
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (!s) return
        if (s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) {
          dropUntil = Date.now() + WINDOW_MS
          var ex = ''
          if (esqlf) { try { var ep = esqlf(a[0]); if (ep && !ep.isNull()) ex = (ep.readCString() || '').slice(0, 90) } catch (e) {} }
          send({ ev: 'READ_WIN', t: Date.now() % 100000, sql: ex })
          return
        }
        if (s.indexOf('feed_channel') >= 0 && (s.slice(0, 7).toUpperCase().indexOf('REPLACE') === 0 || s.slice(0, 6).toUpperCase().indexOf('UPDATE') === 0)) {
          var full = s
          if (esqlf) { try { var ep2 = esqlf(a[0]); if (ep2 && !ep2.isNull()) { var es = ep2.readCString(); if (es) full = es } } catch (e) {} }
          var inWin = Date.now() < dropUntil
          send({ ev: 'FEEDCH', t: Date.now() % 100000, inWin: inWin, sql: full.slice(0, 720) })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
