'use strict'
// hunt22.js — 破时序: 从消息【接收入库】(REPLACE INTO messages)就追 message_id, 远早于读.
//   回执引用被读 message_id; 提前追好 -> 发出时已在手. ascii + 小端 双编码在 sub_649FE5C payload 匹配 -> 丢.
//   也追 me_read IN列表 / read_position chat_id 兜底. message-send 不走此函数, 不误伤发送.
var OFF_ENQ = 0x649fe5c
var KEEP = 400          // 最近 message_id 上限
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
function asciiHex(dec) { var s = ''; for (var i = 0; i < dec.length; i++) s += dec.charCodeAt(i).toString(16); return s }
function leHex(dec) { var v = BigInt(dec), s = ''; for (var i = 0; i < 8; i++) { s += ('0' + Number(v & 0xffn).toString(16)).slice(-2); v >>= 8n } return s }
function bhex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }
whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked' })
  var ids = {}          // dec -> {ascii, le}
  var order = []        // FIFO 控制数量
  var dropped = 0
  function track(dec, why) {
    if (!/^\d{6,}$/.test(dec) || ids[dec]) return
    ids[dec] = { ascii: asciiHex(dec), le: leHex(dec) }
    order.push(dec)
    if (order.length > KEEP) { var old = order.shift(); delete ids[old] }
    if (why) send({ ev: 'TRACK', id: dec, why: why })
  }
  // frontier 丢弃
  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    var keys = order
    if (keys.length) {
      var blob = ''
      try { blob = bhex(a4.add(8).readPointer(), 320) } catch (e) {}
      blob += bhex(a4, 64)
      for (var i = 0; i < keys.length; i++) {
        var e = ids[keys[i]]; if (!e) continue
        if (blob.indexOf(e.ascii) >= 0 || blob.indexOf(e.le) >= 0) {
          dropped++; if (dropped <= 12 || dropped % 30 === 0) send({ ev: 'DROP', n: dropped, a3: a3 & 0xff, id: keys[i] })
          return ptr(0)
        }
      }
    }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'armed' })

  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step'); var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql'); var esqlp = getExp('libsqlcipher.so', 'sqlite3_expanded_sql')
    if (step && sqlp && esqlp) { clearInterval(st); install(step, sqlp, esqlp) }
  }, 200)
  function install(step, sqlp, esqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer']); var esqlf = new NativeFunction(esqlp, 'pointer', ['pointer'])
    var first = true
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (!s) return
        // 接收入库: REPLACE INTO `messages` (id, chat_id, ...) -> 第1值=message_id
        if (s.indexOf('REPLACE INTO `messages`') === 0 || s.indexOf('INSERT INTO `messages`') === 0) {
          var es; try { es = esqlf(a[0]).readCString() } catch (e) { return }
          if (es) { var m = es.match(/VALUES \((\d+)/); if (m) track(m[1], first ? 'msg' : null), first = false }
          return
        }
        // me_read IN列表 + read_position chat_id 兜底
        if (s.indexOf('me_read') >= 0 && s.indexOf('UPDATE') >= 0) {
          var es2; try { es2 = esqlf(a[0]).readCString() } catch (e) { return }
          if (es2) { var mm = es2.match(/IN \(([^)]*)\)/); if (mm) { var parts = mm[1].split(','); for (var i = 0; i < parts.length; i++) { var d = parts[i].trim(); if (/^\d+$/.test(d)) track(d, 'meread') } } }
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
