'use strict'
// hunt20.js — 迭代丢弃定位回执: 只丢 a3==TARGET 的 frontier 发送(仅读窗口内, 避免误伤后台).
//   验证: 对方未读? 收发/聊天正常? 逐个 a3 试出回执类.
var OFF_ENQ = 0x649fe5c
var TARGET_A3 = 2   // 改这个值试不同类: 10/6/2/15
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked', target: TARGET_A3 })
  var readUntil = 0, dropped = 0
  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    if ((a3 & 0xff) === TARGET_A3) {
      dropped++; if (dropped <= 10 || dropped % 50 === 0) send({ ev: 'DROP', n: dropped, a3: a3 & 0xff })
      return ptr(0)
    }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'armed' })
  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step'); var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    if (step && sqlp) { clearInterval(st); install(step, sqlp) }
  }, 200)
  function install(step, sqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (s && (s.indexOf('me_read') >= 0 || s.indexOf('read_position') >= 0) && s.indexOf('UPDATE') >= 0) { readUntil = Date.now() + 15000; send({ ev: 'READ' }) }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
