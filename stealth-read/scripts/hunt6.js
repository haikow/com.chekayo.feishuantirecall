'use strict'
// hunt6.js — 决定性实验: 只跳过 "UPDATE chats SET read_position" 的执行(返回 SQLITE_DONE),
//   保留 messages.me_read 等本地读标记. 验证: 对方是否未读 + 本地红点/已读是否正常.
//   纯 sqlite, 不碰网络.
function getExp(modname, sym) {
  var m = Process.findModuleByName(modname); if (!m) return null
  try { return m.getExportByName(sym) } catch (e) { try { return m.findExportByName(sym) } catch (e2) { return null } }
}
send({ ev: 'hooked' })
var st = setInterval(function () {
  var step = getExp('libsqlcipher.so', 'sqlite3_step')
  var sqlp = getExp('libsqlcipher.so', 'sqlite3_sql')
  if (step && sqlp) { clearInterval(st); install(step, sqlp) }
}, 200)

function install(step, sqlp) {
  var stepN = new NativeFunction(step, 'int', ['pointer'])
  var sqlf = new NativeFunction(sqlp, 'pointer', ['pointer'])
  var blocked = 0
  Interceptor.replace(step, new NativeCallback(function (stmt) {
    try {
      var p = sqlf(stmt)
      if (p && !p.isNull()) {
        var s = p.readCString()
        if (s && s.indexOf('UPDATE') >= 0 && s.indexOf('read_position') >= 0) {
          blocked++
          send({ ev: 'SKIP_READPOS', n: blocked, t: Date.now() % 100000 })
          return 101 // SQLITE_DONE, 不执行该 UPDATE
        }
      }
    } catch (e) {}
    return stepN(stmt)
  }, 'int', ['pointer']))
  send({ ev: 'readpos_suppressed' })
}
