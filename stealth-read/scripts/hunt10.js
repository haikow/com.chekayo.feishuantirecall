'use strict'
// hunt10.js — recon(只读, 不丢包): A 正常读时, 抓清未读红点的 SQL(含绑定真实值).
//   用 sqlite3_expanded_sql 看真实数值. 过滤: 含 unread/badge 或 feed_channel/feed_previews 的写语句.
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
send({ ev: 'hooked' })
var st = setInterval(function () {
  var step = getExp('libsqlcipher.so', 'sqlite3_step')
  var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
  var esqlp = getExp('libsqlcipher.so', 'sqlite3_expanded_sql')
  if (step && sqlp) { clearInterval(st); install(step, sqlp, esqlp) }
}, 200)

function install(step, sqlp, esqlp) {
  var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
  var esqlf = esqlp ? new NativeFunction(esqlp, 'pointer', ['pointer']) : null
  send({ ev: 'sqlite_hooked', expanded: !!esqlf })
  Interceptor.attach(step, {
    onEnter: function (a) {
      var p; try { p = sqlf(a[0]) } catch (e) { return }
      if (!p || p.isNull()) return
      var s; try { s = p.readCString() } catch (e) { return }
      if (!s) return
      var u = s.slice(0, 6).toUpperCase()
      if (u.indexOf('INSERT') !== 0 && u.indexOf('REPLAC') !== 0 && u.indexOf('UPDATE') !== 0) return
      var low = s.toLowerCase()
      var hit = low.indexOf('unread') >= 0 || low.indexOf('badge') >= 0 ||
                low.indexOf('feed_channel') >= 0 || low.indexOf('feed_previews') >= 0 ||
                low.indexOf('feed_preview') >= 0
      if (!hit) return
      var full = s
      if (esqlf) { try { var ep = esqlf(a[0]); if (ep && !ep.isNull()) { var es = ep.readCString(); if (es) full = es } } catch (e) {} }
      send({ ev: 'CLR', t: Date.now() % 100000, sql: full.slice(0, 240) })
    }
  })
}
