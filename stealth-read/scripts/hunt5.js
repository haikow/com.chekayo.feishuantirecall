'use strict'
// hunt5.js — 纯 sqlite(安全), 找已读回执的本地 outbox/command/sync 载体:
//   read_position 写库 = 读触发. 在它前后窗口内, 记录所有【写类】SQL(INSERT/REPLACE/UPDATE/DELETE)
//   去重后逐条上报. 找 read_position 同步用的命令表(非 messages/chats 的可疑表写入).
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
var lark = Process.findModuleByName('liblark.so')
send({ ev: 'hooked', base: lark ? lark.base.toString() : '?' })

var st = setInterval(function () {
  var step = getExp('libsqlcipher.so', 'sqlite3_step')
  var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
  if (step && sqlp) { clearInterval(st); installSqlite(step, sqlp) }
}, 200)

function installSqlite(step, sqlp) {
  var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
  var readUntil = 0
  var seen = {}            // 窗口内去重
  Interceptor.attach(step, {
    onEnter: function (a) {
      var p; try { p = sqlf(a[0]) } catch (e) { return }
      if (!p || p.isNull()) return
      var s; try { s = p.readCString() } catch (e) { return }
      if (!s) return
      // 读触发: 开窗
      if (s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) {
        readUntil = Date.now() + 3000
        seen = {}
        send({ ev: 'READ', t: Date.now() % 100000 })
        return
      }
      if (Date.now() >= readUntil) return
      // 窗口内: 只看写类 SQL
      var u = s.slice(0, 6).toUpperCase()
      if (u.indexOf('INSERT') !== 0 && u.indexOf('REPLAC') !== 0 && u.indexOf('UPDATE') !== 0 && u.indexOf('DELETE') !== 0) return
      var key = s.slice(0, 80)
      if (seen[key]) return
      seen[key] = 1
      send({ ev: 'WSQL', t: Date.now() % 100000, sql: s.slice(0, 110) })
    }
  })
  send({ ev: 'sqlite_hooked' })
}
