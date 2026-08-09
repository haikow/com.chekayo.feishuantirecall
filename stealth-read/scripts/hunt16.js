'use strict'
// hunt16.js — 诊断当前 app 的回执判据(frida 快速迭代).
//   追踪读 id: read_position 的 chat_id + me_read IN列表 message_id (带时间戳).
//   每个 sub_649FE5C 发送: 查 payload 是否含某读 id(ASCII或小端) 或 读报告签名(0a13+19数字); 命中则 dump.
//   目的: 看回执引用哪个 id/编码、时序(发送 vs me_read写库)、命令结构 -> 找可靠判据.
var OFF_ENQ = 0x649fe5c
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
function leHex(dec) { var v = BigInt(dec), s = ''; for (var i = 0; i < 8; i++) { s += ('0' + Number(v & 0xffn).toString(16)).slice(-2); v >>= 8n } return s }
function asciiHex(dec) { var s = ''; for (var i = 0; i < dec.length; i++) s += dec.charCodeAt(i).toString(16); return s }
function bhex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }

whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked' })
  var ids = {}   // dec -> {ascii, le, t}

  Interceptor.attach(base.add(OFF_ENQ), {
    onEnter: function (a) {
      var q1h = '', a4h = ''
      try { q1h = bhex(a[3].add(8).readPointer(), 256) } catch (e) {}
      a4h = bhex(a[3], 64)
      var blob = q1h + a4h
      var a3 = a[2].toInt32() & 0xff
      // 读报告签名: 0a13 + 19 ascii 数字
      var sig = /0a13(3[0-9]){19}/.test(q1h)
      // 命中读 id?
      var hitId = null, enc = null
      for (var d in ids) {
        if (blob.indexOf(ids[d].le) >= 0) { hitId = d; enc = 'LE'; break }
        if (blob.indexOf(ids[d].ascii) >= 0) { hitId = d; enc = 'ASCII'; break }
      }
      if (hitId || sig) {
        send({ ev: 'SEND', t: Date.now() % 100000, a3: a3, hit: hitId, enc: enc, sig: sig, q1: q1h.slice(0, 140) })
      }
    }
  })

  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step'); var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql'); var esqlp = getExp('libsqlcipher.so', 'sqlite3_expanded_sql')
    if (step && sqlp && esqlp) { clearInterval(st); install(step, sqlp, esqlp) }
  }, 200)
  function install(step, sqlp, esqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer']); var esqlf = new NativeFunction(esqlp, 'pointer', ['pointer'])
    function track(dec, why) { if (!ids[dec]) { ids[dec] = { ascii: asciiHex(dec), le: leHex(dec), t: Date.now() % 100000 }; send({ ev: 'TRACK', id: dec, why: why, t: Date.now() % 100000 }) } else ids[dec].t = Date.now() % 100000 }
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (!s) return
        var es = null
        if ((s.indexOf('me_read') >= 0 || s.indexOf('read_position') >= 0 || s.indexOf('message_read_time') >= 0) && (s.indexOf('UPDATE') >= 0 || s.indexOf('REPLACE') >= 0 || s.indexOf('INSERT') >= 0)) {
          try { es = esqlf(a[0]).readCString() } catch (e) {}
        }
        if (!es) return
        if (s.indexOf('me_read') >= 0) { var m = es.match(/IN \(([^)]*)\)/); if (m) { var parts = m[1].split(','); for (var i = 0; i < parts.length; i++) { var d = parts[i].trim(); if (/^\d+$/.test(d)) track(d, 'me_read') } } }
        if (s.indexOf('read_position') >= 0) { var mc = es.match(/`chats`\.`id`\s*=\s*(\d+)/); if (mc) track(mc[1], 'chat') }
        if (s.indexOf('message_read_time') >= 0) { var mr = es.match(/VALUES \((\d+)/); if (mr) track(mr[1], 'mrt') }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
