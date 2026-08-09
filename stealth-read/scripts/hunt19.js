'use strict'
// hunt19.js — dump 读窗口发送的原始结构, 找命令号整数 + descriptor 区分.
//   每个发送: a3, a4[0/1/2] 原始qword值, a1[8]值, a4[2]对象头32B. 去重(按 a3+a4[2]vtable). 找 cmd-id(1021等) 整数.
var OFF_ENQ = 0x649fe5c
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
function rd(p, off) { try { return p.add(off).readPointer() } catch (e) { return null } }
function bhex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }
whenLark(function (lark) {
  var base = lark.base, lo = base, hi = base.add(0x6600000)
  send({ ev: 'hooked' })
  var readUntil = 0, seen = {}
  function rel(p) { if (!p) return '0'; if (p.compare(lo) >= 0 && p.compare(hi) < 0) return '+0x' + p.sub(base).toString(16); return p.toString() }
  Interceptor.attach(base.add(OFF_ENQ), {
    onEnter: function (a) {
      if (Date.now() >= readUntil) return
      var a1 = a[0], a4 = a[3], a3 = a[2].toInt32() & 0xff
      var a40 = rd(a4, 0), a41 = rd(a4, 8), a42 = rd(a4, 16), a18 = rd(a1, 8)
      var vt = a42 ? rd(a42, 0) : null
      var key = a3 + '|' + rel(vt)
      if (seen[key]) return
      seen[key] = 1
      send({ ev: 'S', a3: a3, a4_0: rel(a40), a4_1: rel(a41), a4_2: rel(a42), a1_8: rel(a18),
             vt: rel(vt), obj: a42 ? bhex(a42, 40) : '', q1head: a41 ? bhex(a41, 48) : '' })
    }
  })
  send({ ev: 'enq_hooked' })
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
        if (s && (s.indexOf('me_read') >= 0 || s.indexOf('read_position') >= 0) && s.indexOf('UPDATE') >= 0) { readUntil = Date.now() + 12000; seen = {}; send({ ev: 'READ' }) }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
