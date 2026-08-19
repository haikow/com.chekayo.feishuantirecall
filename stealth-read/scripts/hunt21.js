'use strict'
// hunt21.js — 丢弃独立命令类型(a4[2] 对象的 vtable == base+0x39f0608, 不同 channel). 疑似回执.
var OFF_ENQ = 0x649fe5c
var VT_OFF = 0x39f0608
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
whenLark(function (lark) {
  var base = lark.base, vtTarget = base.add(VT_OFF)
  send({ ev: 'hooked', vt: '0x' + VT_OFF.toString(16) })
  var dropped = 0
  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    var hit = false
    try { var d = a4.add(16).readPointer(); var vt = d.readPointer(); if (vt.equals(vtTarget)) hit = true } catch (e) {}
    if (hit) { dropped++; if (dropped <= 12 || dropped % 30 === 0) send({ ev: 'DROP', n: dropped, a3: a3 & 0xff }); return ptr(0) }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'armed' })
})
