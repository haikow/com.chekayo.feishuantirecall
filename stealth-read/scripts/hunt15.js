'use strict'
// hunt15.js — 完整方案: ①内容法丢已读回执(frontier) + ②本地重算红点(feed_channel.new_message_count).
//   ① sub_649FE5C: payload 含"最近读过 chat 的 ASCII 十进制 id"则丢(return 0). 通杀立即+周期回执.
//   ② sqlite3_step: 拦 REPLACE INTO feed_channel, 把 new_message_count(第10参) 重绑为
//      max(0, last_message_position - read_position)(本地真实未读) → 读完红点=0, 新消息照常+1.
//   纯本地+丢出站, 不破坏发收.
var OFF_ENQ = 0x649fe5c
var ACTIVE_MS = 600000
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
function asciiHexOfDec(d) { var s = ''; for (var i = 0; i < d.length; i++) s += d.charCodeAt(i).toString(16); return s }
function bufHex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }

whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked' })
  var chats = {}     // chatId -> {rp, lmp}
  var active = {}    // asciiHex -> expireMs
  var dropped = 0, rebound = 0

  // ① 回执丢弃
  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    var keys = Object.keys(active)
    if (keys.length) {
      var now = Date.now(), blob = ''
      try { blob = bufHex(a4.add(8).readPointer(), 256) } catch (e) {}
      blob += bufHex(a4, 64)
      for (var i = 0; i < keys.length; i++) {
        var k = keys[i]
        if (active[k] < now) { delete active[k]; continue }
        if (blob.indexOf(k) >= 0) { dropped++; if (dropped <= 8 || dropped % 25 === 0) send({ ev: 'DROP', n: dropped, a3: a3 & 0xff }); return ptr(0) }
      }
    }
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))

  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step')
    var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    var esqlp = getExp('libsqlcipher.so', 'sqlite3_expanded_sql')
    var bindp = getExp('libsqlcipher.so', 'sqlite3_bind_int64')
    if (step && sqlp && esqlp && bindp) { clearInterval(st); install(step, sqlp, esqlp, bindp) }
  }, 200)

  function install(step, sqlp, esqlp, bindp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
    var esqlf = new NativeFunction(esqlp, 'pointer', ['pointer'])
    var bindI64 = new NativeFunction(bindp, 'int', ['pointer', 'int', 'int64'])
    Interceptor.attach(step, {
      onEnter: function (a) {
        var stmt = a[0]
        var p; try { p = sqlf(stmt) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (!s) return

        // 跟踪 chats 的 read_position / last_message_position
        if (s.indexOf('UPDATE') >= 0 && s.indexOf('`chats`') >= 0 &&
            (s.indexOf('read_position') >= 0 || s.indexOf('last_message_position') >= 0)) {
          var es; try { es = esqlf(stmt).readCString() } catch (e) { es = null }
          if (es) {
            var mid = es.match(/`chats`\.`id`\s*=\s*(\d+)/)
            if (mid) {
              var id = mid[1]; if (!chats[id]) chats[id] = { rp: -1, lmp: -1 }
              var mrp = es.match(/`read_position`\s*=\s*(\d+)/); if (mrp) chats[id].rp = parseInt(mrp[1])
              var mlp = es.match(/`last_message_position`\s*=\s*(\d+)/); if (mlp) chats[id].lmp = parseInt(mlp[1])
              if (mrp) { // 一次读 -> 标记活跃(丢回执)
                var ah = asciiHexOfDec(id); var fresh = !active[ah]; active[ah] = Date.now() + ACTIVE_MS
                if (fresh) send({ ev: 'ACTIVE', chatId: id })
              }
            }
          }
          return
        }

        // ② 重算 feed_channel 红点
        if (s.indexOf('feed_channel') >= 0 && s.slice(0, 7).toUpperCase().indexOf('REPLACE') === 0) {
          var ef; try { ef = esqlf(stmt).readCString() } catch (e) { ef = null }
          if (!ef) return
          var mv = ef.match(/VALUES\s*\(([^)]*)\)/i); if (!mv) return
          var vals = mv[1].split(',')
          if (vals.length < 10) return
          var id2 = vals[0].trim()
          var c = chats[id2]
          if (!c || c.rp < 0 || c.lmp < 0) return
          var desired = c.lmp - c.rp; if (desired < 0) desired = 0
          var cur = parseInt(vals[9].trim())
          if (!isNaN(cur) && cur !== desired) {
            try { bindI64(stmt, 10, desired); rebound++; if (rebound <= 8 || rebound % 25 === 0) send({ ev: 'BADGE_FIX', chatId: id2, from: cur, to: desired, n: rebound }) } catch (e) {}
          }
          return
        }
      }
    })
    send({ ev: 'armed' })
  }
})
