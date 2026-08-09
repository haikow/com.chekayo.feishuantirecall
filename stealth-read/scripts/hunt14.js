'use strict'
// hunt14.js — 内容法实测: 丢弃 payload 含"最近读过 chat 的 ASCII 十进制 id"的 frontier 发送.
//   读报告把 chat_id 编成 ASCII 字符串(0a13<id>...), 其它命令多用二进制 LE -> 按 ASCII 形丢, 通杀立即+周期.
//   读后该 chat 标记活跃 5min. 验证: 对方未读(含30s后) + 发收正常. sub_649FE5C 返回值不被用 -> 丢安全.
var OFF_ENQ = 0x649fe5c
var ACTIVE_MS = 300000
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
function asciiHexOfDec(dec) { var s = ''; for (var i = 0; i < dec.length; i++) s += dec.charCodeAt(i).toString(16); return s }
function bufHex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }
whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked' })
  var active = {}   // asciiHex -> expireMs
  var dropped = 0, passed = 0

  var enq = base.add(OFF_ENQ)
  var orig = new NativeFunction(enq, 'pointer', ['pointer', 'pointer', 'int', 'pointer'])
  Interceptor.replace(enq, new NativeCallback(function (a1, a2, a3, a4) {
    var keys = Object.keys(active)
    if (keys.length) {
      var now = Date.now()
      var hx = ''
      try { var q1 = a4.add(8).readPointer(); hx = bufHex(q1, 256) } catch (e) {}
      var ah = bufHex(a4, 64)
      var blob = hx + ah
      for (var i = 0; i < keys.length; i++) {
        var k = keys[i]
        if (active[k] < now) { delete active[k]; continue }
        if (blob.indexOf(k) >= 0) {
          dropped++
          send({ ev: 'DROP_RECEIPT', n: dropped, a3: a3 & 0xff, t: now % 100000 })
          return ptr(0)
        }
      }
    }
    passed++
    return orig(a1, a2, a3, a4)
  }, 'pointer', ['pointer', 'pointer', 'int', 'pointer']))
  send({ ev: 'content_drop_armed' })

  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step')
    var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    var esqlp = getExp('libsqlcipher.so', 'sqlite3_expanded_sql')
    if (step && sqlp && esqlp) { clearInterval(st); install(step, sqlp, esqlp) }
  }, 200)
  function install(step, sqlp, esqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
    var esqlf = new NativeFunction(esqlp, 'pointer', ['pointer'])
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (!s || s.indexOf('read_position') < 0 || s.indexOf('UPDATE') < 0) return
        var ep, es; try { ep = esqlf(a[0]); es = ep.readCString() } catch (e) { return }
        if (!es) return
        var m = es.match(/id`?\s*=\s*(\d+)/)
        if (m) {
          var ah = asciiHexOfDec(m[1])
          var fresh = !active[ah]
          active[ah] = Date.now() + ACTIVE_MS
          if (fresh) send({ ev: 'ACTIVE', chatId: m[1], ascii: ah, t: Date.now() % 100000 })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
