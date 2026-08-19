'use strict'
// 已读不回执 原型：NOP 掉 put_read 的发送调用(0x5aee608)，请求照生成不发送
var lark = Process.findModuleByName('liblark.so')
var base = lark.base

var SITE = base.add(0x5aee608)       // bl 0x58da644 (send put_read request)
var GEN = base.add(0x58771d0)        // gen_put_req
var SEND = base.add(0x58da644)       // send/dispatch
var NOP = 0xD503201F
var origWord = Memory.readU32(SITE)
var blocked = false

function setBlock(on) {
  Memory.patchCode(SITE, 4, function () {
    Memory.writeU32(SITE, on ? NOP : origWord)
  })
  blocked = on
  send({ ev: 'block_set', on: on })
}

var stats = { gen: 0, send: 0 }

Interceptor.attach(GEN, { onEnter: function () { stats.gen++; send({ ev: 'gen_put_req', n: stats.gen }) } })
Interceptor.attach(SEND, { onEnter: function () { stats.send++; send({ ev: 'send_call', n: stats.send }) } })

// 默认屏蔽
setBlock(true)

rpc.exports = {
  block: function (on) { setBlock(!!on); return blocked },
  status: function () { return { blocked: blocked, gen: stats.gen, send: stats.send } }
}

send({ ev: 'ready', origWord: '0x' + (origWord >>> 0).toString(16), blocked: blocked, note: 'put_read send NOP-ed; gen still runs' })
setInterval(function () { send({ ev: 'beat', gen: stats.gen, send: stats.send, blocked: blocked }) }, 8000)
