'use strict'
// hunt17.js — 测"读报告签名"判据: 丢弃所有 payload 含 0a13+19数字(read-report) 的 frontier 发送.
//   不依赖 id 追踪/时序/编码. 验证: 对方未读? 收发正常? chat 能开?
var OFF_ENQ = 0x649fe5c
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
function bhex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }
whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked' })
  var dropped = 0
  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  var re = /0a13(3[0-9]){19}/
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    var h = ''
    try { h = bhex(a4.add(8).readPointer(), 300) } catch (e) {}
    if (re.test(h)) {
      dropped++
      send({ ev: 'DROP_SIG', n: dropped, a3: a3 & 0xff })
      return ptr(0)
    }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'armed' })
})
