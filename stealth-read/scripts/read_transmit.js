'use strict'
// 防已读 transmit-chokepoint 验证脚本 (7.69.6)
// 复核 cpp 里 OFF_TRANSMIT 0x5aef148 的"逐条派发读回执"假设, 并能切到 drop 模式做双账号实测.
//   MODE='observe' : 只统计/打印, 确认开聊天读消息时 transmit 真被命中 (含 cmd=0x28 包).
//   MODE='drop'    : Interceptor.replace transmit 直接返回 0 = 回执不出门, 再到对方端看是否仍显示已读.
var MODE = 'observe'

var lark = Process.findModuleByName('liblark.so')
if (!lark) { send({ ev: 'fatal', e: 'liblark.so not found' }) } else {
  var base = lark.base
  send({ ev: 'base', base: base.toString(), mode: MODE })

  var OFF = {
    pack:     0x6111d8c, // 统一打包函数 w0=cmd id
    gpp:      0x5aec998, // gen_put_packets (movz w0,0x28)
    poll:     0x5aed350, // read send poll
    caller:   0x5aed840, // transmit 调用者 (read 专属)
    transmit: 0x5aef148, // ★ 逐条派发读回执
    fg:       0x5b36a88  // fg im.message.put_read 闸
  }
  var cnt = {}
  function bump(k) { cnt[k] = (cnt[k] || 0) + 1; return cnt[k] }

  // 统一打包: 读 w0=cmd, 高亮 0x28 (PUT_READ_MESSAGES=40)
  try {
    Interceptor.attach(base.add(OFF.pack), {
      onEnter: function (a) {
        var cmd = a[0].toInt32() & 0xffffffff
        if (cmd === 0x28) send({ ev: 'PACK_PUT_READ', cmd: cmd, n: bump('pack28') })
      }
    })
  } catch (e) { send({ ev: 'err', k: 'pack', e: '' + e }) }

  ;['gpp', 'poll', 'caller', 'transmit', 'fg'].forEach(function (k) {
    try {
      Interceptor.attach(base.add(OFF[k]), {
        onEnter: function () { send({ ev: 'hit', k: k, n: bump(k) }) }
      })
    } catch (e) { send({ ev: 'err', k: k, e: '' + e }) }
  })

  if (MODE === 'drop') {
    try {
      Interceptor.replace(base.add(OFF.transmit), new NativeCallback(function () {
        send({ ev: 'DROP', n: bump('drop') })
        return ptr(0)            // 回执不派发; caller 忽略返回, 状态机照常推进
      }, 'pointer', ['pointer', 'pointer', 'pointer', 'pointer']))
      send({ ev: 'drop_armed' })
    } catch (e) { send({ ev: 'err', k: 'drop', e: '' + e }) }
  }

  send({ ev: 'ready', mode: MODE })
  setInterval(function () { send({ ev: 'beat', counts: cnt }) }, 5000)
}
