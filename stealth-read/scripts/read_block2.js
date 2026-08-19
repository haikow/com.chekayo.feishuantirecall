'use strict'
// 原型 v2：hook 0x5774c64，命令名=auto_open_v2 时强制返回0，跳过其构建/发送
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
var TARGET = 'im.chat.auto_open_v2'
var stats = { calls: 0, auto_open: 0, blocked: 0 }

try {
  Interceptor.attach(base.add(0x5774c64), {
    onEnter: function (args) {
      this.cmd = ''
      try {
        var len = args[2].toInt32() & 0x3ff
        if (len > 0 && len < 256) this.cmd = Memory.readUtf8String(args[1], len)
      } catch (e) {}
      stats.calls++
      if (this.cmd === TARGET) { stats.auto_open++; this.hit = true }
    },
    onLeave: function (retval) {
      if (this.hit) {
        var was = retval.toInt32()
        retval.replace(ptr(0))   // 强制返回 0 -> cbz 跳过构建/发送
        stats.blocked++
        send({ ev: 'blocked_auto_open', prev_ret: was })
      }
    }
  })
  send({ ev: 'hooked', what: '0x5774c64' })
} catch (e) { send({ ev: 'hk_err', e: '' + e }) }

send({ ev: 'ready' })
setInterval(function () { send({ ev: 'beat', calls: stats.calls, auto_open: stats.auto_open, blocked: stats.blocked }) }, 8000)
