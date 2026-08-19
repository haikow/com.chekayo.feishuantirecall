'use strict'
// auto_open_v2 触发时 Stalker 跟踪本线程 150ms 内的所有 call，找"提交发送"
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
var textStart = 0x2fd5000, textEnd = 0x6310000 // liblark .text 大致范围
var armed = false

function off(p) { try { return '0x' + p.sub(base).toString(16) } catch (e) { return '?' } }

function stalkFrom(triggerLabel, addr) {
  Interceptor.attach(addr, {
    onEnter: function () {
      if (armed) return
      armed = true
      var tid = this.threadId
      var calls = {}
      send({ ev: 'stalk_start', from: triggerLabel })
      Stalker.follow(tid, {
        events: { call: true },
        onCallSummary: function (s) {
          for (var t in s) {
            var p = ptr(t)
            // 只记 liblark .text 内的目标
            var o = -1; try { o = p.sub(base).toInt32() } catch (e) {}
            if (o >= textStart && o < textEnd) calls[o] = (calls[o] || 0) + s[t]
          }
        }
      })
      setTimeout(function () {
        try { Stalker.unfollow(tid) } catch (e) {}
        try { Stalker.flush() } catch (e) {}
        var arr = Object.keys(calls).map(function (k) { return [parseInt(k), calls[k]] }).sort(function (a, b) { return b[1] - a[1] })
        send({ ev: 'stalk_done', total: arr.length, top: arr.slice(0, 50).map(function (x) { return ['0x' + x[0].toString(16), x[1]] }) })
        setTimeout(function () { armed = false }, 300)
      }, 150)
    }
  })
}

stalkFrom('auto_open_v2@0x59dc03c', base.add(0x59dc03c))
send({ ev: 'ready' })
setInterval(function () { send({ ev: 'beat' }) }, 8000)
