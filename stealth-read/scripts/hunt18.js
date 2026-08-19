'use strict'
// hunt18.js — 命令号猎手: 在 sub_649FE5C 找 frontier 读命令号(1021/1067/2224/2234/2263)在帧里的位置.
//   读窗口内, 每个发送扫 a1[8]缓冲 / a4[0..2]各指向缓冲 / q1, 找命令号 varint, 命中即报(哪个缓冲+偏移+a3+descriptor).
var OFF_ENQ = 0x649fe5c
// 命令号 -> protobuf varint (hex)
var CMDS = { '1021_meRead': 'fd07', '1067_chatLastReadPos': 'ab08', '2224_threadMeRead': 'b011', '2234_docMeRead': 'ba11', '2263_chatAppMeRead': 'd711' }
function getExp(m, s) { var x = Process.findModuleByName(m); if (!x) return null; try { return x.getExportByName(s) } catch (e) { try { return x.findExportByName(s) } catch (e2) { return null } } }
function whenLark(cb) { var m = Process.findModuleByName('liblark.so'); if (m) { cb(m); return } var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50) }
function bhex(p, n) { try { var a = new Uint8Array(p.readByteArray(n)); var s = ''; for (var i = 0; i < a.length; i++) s += ('0' + a[i].toString(16)).slice(-2); return s } catch (e) { return '' } }
whenLark(function (lark) {
  var base = lark.base, lo = base, hi = base.add(0x6600000)
  send({ ev: 'hooked' })
  var readUntil = 0
  function descOf(p) { if (!p || p.isNull()) return '0'; if (p.compare(lo) >= 0 && p.compare(hi) < 0) return 'off:0x' + p.sub(base).toString(16); try { var vt = p.readPointer(); if (vt.compare(lo) >= 0 && vt.compare(hi) < 0) return 'vt:0x' + vt.sub(base).toString(16) } catch (e) {} return 'ext' }
  Interceptor.attach(base.add(OFF_ENQ), {
    onEnter: function (a) {
      if (Date.now() >= readUntil) return
      var a1 = a[0], a4 = a[3], a3 = a[2].toInt32() & 0xff
      // 收集候选缓冲
      var bufs = {}
      bufs['q1'] = bhex(safePtr(a4, 8), 320)
      bufs['a4_0'] = bhex(safePtr(a4, 0), 192)
      bufs['a4_2'] = bhex(safePtr(a4, 16), 192)
      bufs['a1_8'] = bhex(safePtr(a1, 8), 192)
      bufs['a1_0'] = bhex(a1, 64)
      var hits = []
      for (var name in bufs) {
        var h = bufs[name]; if (!h) continue
        for (var cmd in CMDS) { var idx = h.indexOf(CMDS[cmd]); if (idx >= 0) hits.push(cmd + '@' + name + '+' + (idx / 2)) }
      }
      if (hits.length) send({ ev: 'CMD', t: Date.now() % 100000, a3: a3, desc: descOf(safePtr(a4, 16)), hits: hits })
    }
  })
  function safePtr(base_, off) { try { return base_.add(off).readPointer() } catch (e) { return ptr(0) } }
  send({ ev: 'enq_hooked' })

  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step'); var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    if (step && sqlp) { clearInterval(st); install(step, sqlp) }
  }, 200)
  function install(step, sqlp) {
    var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
    Interceptor.attach(step, {
      onEnter: function (a) {
        var p; try { p = sqlf(a[0]) } catch (e) { return }
        if (!p || p.isNull()) return
        var s; try { s = p.readCString() } catch (e) { return }
        if (s && (s.indexOf('me_read') >= 0 || s.indexOf('read_position') >= 0) && (s.indexOf('UPDATE') >= 0)) { readUntil = Date.now() + 12000; send({ ev: 'READ', t: Date.now() % 100000 }) }
      }
    })
    send({ ev: 'sqlite_hooked' })
  }
})
