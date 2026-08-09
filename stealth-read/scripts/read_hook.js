'use strict'
// 挂钩 put_read 管线候选点，确认谁在"读"时亮 + 0x57749b0 是否为命令分发中枢
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
function off(p) { return '0x' + p.sub(base).toString(16) }
function bt(ctx, n) {
  return Thread.backtrace(ctx, Backtracer.ACCURATE).slice(0, n || 8).map(function (a) {
    var m = Process.findModuleByAddress(a); return (m ? (m.name + '+' + off(a)) : ('0x' + a.toString(16)))
  })
}

// 1) gen_put_req 入口 0x58771d0
try {
  Interceptor.attach(base.add(0x58771d0), {
    onEnter: function () { send({ ev: 'gen_put_req', bt: bt(this.context, 6) }) }
  })
  send({ ev: 'hooked', what: 'gen_put_req@0x58771d0' })
} catch (e) { send({ ev: 'hk_err', what: 'gen_put_req', e: '' + e }) }

// 2) 命令名分发候选 0x57749b0 —— 记录每次调用的 (x1 字符串, x2 长度)
try {
  Interceptor.attach(base.add(0x57749b0), {
    onEnter: function (args) {
      try {
        var len = args[2].toInt32() & 0x3ff
        var s = ''
        if (len > 0 && len < 256) { try { s = Memory.readUtf8String(args[1], len) } catch (e) {} }
        send({ ev: 'dispatch', cmd: s, len: len })
      } catch (e) { send({ ev: 'dispatch_err', e: '' + e }) }
    }
  })
  send({ ev: 'hooked', what: 'dispatch@0x57749b0' })
} catch (e) { send({ ev: 'hk_err', what: 'dispatch', e: '' + e }) }

send({ ev: 'ready' })
setInterval(function () { send({ ev: 'beat' }) }, 8000)
