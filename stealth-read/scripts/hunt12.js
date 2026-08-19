'use strict'
// hunt12.js — 内容 recon: 在 frontier 终端入队 sub_649FE5C, 找 chat_id 是否在 payload 明文可见.
//   从 read_position 的 WHERE 取 chatId -> 8字节 LE. 读后 40s 内, 扫每次发送的 a4[0..2] 指向的缓冲, 找 chatId.
//   命中 => 内容法可行(可按 chat_id 精确丢回执, 通杀立即+周期). 只读, 限流, 安全.
var OFF_ENQ = 0x649fe5c
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
function u64le(decStr) { // 十进制 -> 8字节小端 hex
  var bytes = []
  // 用 BigInt 安全
  var v = BigInt(decStr)
  for (var i = 0; i < 8; i++) { bytes.push(Number(v & 0xffn)); v >>= 8n }
  return bytes
}
function hexOf(arr) { var s = ''; for (var i = 0; i < arr.length; i++) s += ('0' + arr[i].toString(16)).slice(-2); return s }
function bufHex(p, n) {
  try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' }
}
whenLark(function (lark) {
  var base = lark.base
  send({ ev: 'hooked' })
  var readActiveUntil = 0
  var targetHex = null, targetDec = null
  var scans = 0

  Interceptor.attach(base.add(OFF_ENQ), {
    onEnter: function (a) {
      if (Date.now() >= readActiveUntil || !targetHex) return
      if (scans > 60) return
      var a4 = a[3]
      var q1 = null, q2 = null
      try { q1 = a4.add(8).readPointer() } catch (e) {}
      try { q2 = a4.add(16).readPointer() } catch (e) {}
      var hq1 = q1 ? bufHex(q1, 192) : ''
      var ha = bufHex(a4, 64)
      var hit = (hq1 && hq1.indexOf(targetHex) >= 0) || (ha && ha.indexOf(targetHex) >= 0)
      scans++
      if (hit) {
        var dt = readActiveUntil - Date.now() // 越小=离读越久
        send({ ev: 'PAY', t: Date.now() % 100000, dtRemain: dt, a3: a[2].toInt32() & 0xff,
               desc: (q2 && q2.compare(base) >= 0 && q2.compare(base.add(0x6600000)) < 0) ? '0x' + q2.sub(base).toString(16) : 'ext',
               a4: ha, q1: hq1 })
      } else if (scans % 20 === 0) send({ ev: 'scan', n: scans })
    }
  })
  send({ ev: 'enq_hooked' })

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
        var ep; try { ep = esqlf(a[0]) } catch (e) { return }
        var es; try { es = ep.readCString() } catch (e) { return }
        if (!es) return
        var m = es.match(/chats`?\.`?id`?\s*=\s*(\d+)/)
        if (!m) m = es.match(/id`?\s*=\s*(\d+)/)
        if (m) {
          targetDec = m[1]; targetHex = hexOf(u64le(targetDec)); scans = 0
          readActiveUntil = Date.now() + 40000
          send({ ev: 'READ', chatId: targetDec, le: targetHex, t: Date.now() % 100000 })
        }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
