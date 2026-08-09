'use strict'
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
var armed = false
function dump(from, to) {} // noop

Interceptor.attach(base.add(0x59dc03c), {
  onEnter: function () {
    if (armed) return; armed = true
    var tid = this.threadId
    var calls = {}
    send({ ev: 'stalk_start' })
    Stalker.follow(tid, {
      events: { call: true },
      onCallSummary: function (s) {
        for (var t in s) {
          var o = -1; try { o = ptr(t).sub(base).toInt32() } catch (e) {}
          // 排除 0x30xxxx 工具区，排除过高频(>5)，聚焦业务/网络区
          if (o >= 0x4000000 && o < 0x6310000) {
            calls[o] = (calls[o] || 0) + s[t]
          }
        }
      }
    })
    setTimeout(function () {
      try { Stalker.unfollow(tid) } catch (e) {}
      try { Stalker.flush() } catch (e) {}
      var low = Object.keys(calls).map(function (k) { return [parseInt(k), calls[k]] }).filter(function (x) { return x[1] <= 5 }).sort(function (a, b) { return a[0] - b[0] })
      send({ ev: 'stalk_done', low_count_targets: low.map(function (x) { return ['0x' + x[0].toString(16), x[1]] }) })
      setTimeout(function () { armed = false }, 300)
    }, 150)
  }
})
send({ ev: 'ready' })
setInterval(function () { send({ ev: 'beat' }) }, 8000)
