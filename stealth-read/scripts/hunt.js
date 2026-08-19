'use strict'
// hunt.js v3 — 数据流 hunt: hook sqlite "UPDATE chats SET read_position"(A 读的可靠触发),
// 命中后 Stalker 跟当前线程 60ms, 抓它在 liblark 里调用的函数集 = 读位置上报的构造/入队链.
function whenLark(cb) {
  var m = Process.findModuleByName('liblark.so')
  if (m) { cb(m); return }
  var t = setInterval(function () { var mm = Process.findModuleByName('liblark.so'); if (mm) { clearInterval(t); cb(mm) } }, 50)
}
whenLark(function (lark) {
  var base = lark.base, lo = base, hi = base.add(0x6600000)
  send({ ev: 'hooked', base: base.toString(), sslw: 'n/a' })   // 让 driver 认得 hooked, 锁主进程
  function getExp(modname, sym) {
    var m = Process.findModuleByName(modname); if (!m) return null
    try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
  }
  var st = setInterval(function () {
    var step = getExp('libsqlcipher.so', 'sqlite3_step')
    var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
    if (step && sqlp) { clearInterval(st); installSqlite(step, sqlp) }
  }, 200)
  function installSqlite(step, sqlp) {
  var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
  var busy = false
  send({ ev: 'sqlite_resolved' })
  Interceptor.attach(step, {
    onEnter: function (a) {
      if (busy) return
      var p; try { p = sqlf(a[0]) } catch (e) { return }
      if (!p || p.isNull()) return
      var s; try { s = p.readCString() } catch (e) { return }
      if (!s || s.indexOf('read_position') < 0 || s.indexOf('UPDATE') < 0) return
      busy = true
      send({ ev: 'READ_POS_SQL', tid: this.threadId, sql: s.substring(0, 70) })
      var targets = {}
      try {
        Stalker.follow(this.threadId, {
          events: { call: true },
          onReceive: function (ev) {
            var calls = Stalker.parse(ev, { annotate: false, stringify: false })
            for (var i = 0; i < calls.length; i++) {
              var to = calls[i][2]
              if (!to) continue
              var ta = ptr(to)
              if (ta.compare(lo) >= 0 && ta.compare(hi) < 0) {
                var k = '0x' + ta.sub(base).toString(16)
                targets[k] = (targets[k] || 0) + 1
              }
            }
          }
        })
      } catch (e) { send({ ev: 'stalk_err', e: '' + e }); busy = false; return }
      setTimeout(function () {
        try { Stalker.unfollow(); Stalker.flush() } catch (e) {}
        send({ ev: 'STALK', targets: targets })
        busy = false
      }, 60)
    }
  })
  send({ ev: 'sqlite_hooked' })
  }
})
