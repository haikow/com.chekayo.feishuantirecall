'use strict'
// 撒网：指令级 hook 所有已读相关代码点，看哪个在"读"时真亮
var lark = Process.findModuleByName('liblark.so')
var base = lark.base

// 先还原之前 NOP 的 0x5aee608（干净起见）
try { Memory.patchCode(base.add(0x5aee608), 4, function () { Memory.writeU32(base.add(0x5aee608), 0x97f7b00f >>> 0) }) } catch (e) { send({ ev: 'revert_err', e: '' + e }) }

var probes = [
  [0x5b36fc8, 'put_read cmd-log'],
  [0x58771fc, 'gen_put_req span'],
  [0x5971de4, 'rm::logic put_read#1'],
  [0x59a9cfc, 'pipe_sync/auto_open'],
  [0x59dc03c, 'im.chat.auto_open_v2'],
  [0x59dc0c4, 'sdk_auto_open_translate'],
  [0x59dc1b0, 'rm::logic put_read#2'],
  [0x59dc394, 'rm::logic put_read#3'],
  [0x5a05f24, 'receive::consume']
]
var counts = {}
probes.forEach(function (p) {
  var off = p[0], label = p[1]; counts[label] = 0
  try {
    Interceptor.attach(base.add(off), {
      onEnter: function () { counts[label]++; send({ ev: 'probe', label: label, n: counts[label] }) }
    })
  } catch (e) { send({ ev: 'probe_err', label: label, e: '' + e }) }
})
send({ ev: 'ready', n: probes.length })
setInterval(function () { send({ ev: 'beat', counts: counts }) }, 8000)
