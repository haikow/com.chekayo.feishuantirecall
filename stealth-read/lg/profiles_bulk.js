// profiles_bulk.js —— 批量捞在职同事富资料(部门/邮箱/工号/职务/上级)
//
// 思路(同 resign_check.js): hook 进程内 libsqlcipher, 借飞书已解密的 contact.db 活句柄,
// 就地跑我们自己的 SELECT。不同点: 这里不点资料页, 而是【直接把 chatter_profiles 全表 dump 出来】,
// 列名自适应(PRAGMA/column_name), 所以事先不知道真实列名也能跑。
//
// 用法:
//   1) 设备起 frida-server(root)。
//   2) 电脑: frida -U -n 飞书 -l stealth-read/lg/profiles_bulk.js   (或 -n Feishu / -f com.ss.android.lark -F)
//   3) 飞书里进【通讯录 → 组织架构 / 我的部门】上下滑一圈 —— 触发飞书读 contact.db, 句柄一到就 dump。
//      多账号: 每个账号各滑一次(各自 contact.db)。
//   4) 结果落在设备: /data/data/com.ss.android.lark/files/resign_tracker/profiles_bulk_<tenant>.json
//      电脑拉取: adb shell "su -c 'cat /data/data/com.ss.android.lark/files/resign_tracker/profiles_bulk_*.json'"
//      (无 su 就用 run-as, 或 frida 控制台里会同时打印一份精简日志)
//
// 首跑是【侦察】: 输出 tables 列表 + 所有 *profile*/*depart*/*leader*/*employee* 表的建表语句,
// 据此确认"上级/部门"到底在哪张表、靠哪个 key join, 再把下面 DUMP_SQL 改成精准 JOIN 即可。

(function () {
  var OUT_DIR = '/data/data/com.ss.android.lark/files/resign_tracker';
  var PS = Process.pointerSize;

  // 想全量就用 SELECT *; 若确认了 join key, 可改成只取在职:
  //   SELECT p.* FROM chatter_profiles p JOIN chatters c ON c.id=p.<key> WHERE c.is_resigned=0
  var DUMP_SQL = 'SELECT * FROM chatter_profiles';

  function whenSql(cb) {
    var m = Process.findModuleByName('libsqlcipher.so');
    if (m) { cb(m); return; }
    var t = setInterval(function () {
      var mm = Process.findModuleByName('libsqlcipher.so');
      if (mm) { clearInterval(t); cb(mm); }
    }, 100);
  }

  whenSql(function (m) {
    var e = {};
    ['sqlite3_prepare_v2', 'sqlite3_step', 'sqlite3_finalize', 'sqlite3_db_handle',
     'sqlite3_column_count', 'sqlite3_column_name', 'sqlite3_column_text',
     'sqlite3_db_filename'].forEach(function (n) { e[n] = m.getExportByName(n); });

    var prep   = new NativeFunction(e.sqlite3_prepare_v2, 'int', ['pointer', 'pointer', 'int', 'pointer', 'pointer']);
    var step   = new NativeFunction(e.sqlite3_step, 'int', ['pointer']);
    var fin    = new NativeFunction(e.sqlite3_finalize, 'int', ['pointer']);
    var dbh    = new NativeFunction(e.sqlite3_db_handle, 'pointer', ['pointer']);
    var ccount = new NativeFunction(e.sqlite3_column_count, 'int', ['pointer']);
    var cname  = new NativeFunction(e.sqlite3_column_name, 'pointer', ['pointer', 'int']);
    var ctext  = new NativeFunction(e.sqlite3_column_text, 'pointer', ['pointer', 'int']);
    var fname  = new NativeFunction(e.sqlite3_db_filename, 'pointer', ['pointer', 'pointer']);

    // 通用查询: 返回 {cols:[...], rows:[{col:val}...]}; 表不存在/prepare 失败返回 null。
    function query(db, sql) {
      var pp = Memory.alloc(PS);
      var cs = Memory.allocUtf8String(sql);
      if (prep(db, cs, -1, pp, ptr(0)) !== 0) return null;
      var st = pp.readPointer();
      var n = ccount(st);
      var cols = [];
      for (var i = 0; i < n; i++) {
        var np = cname(st, i);
        cols.push(np.isNull() ? ('c' + i) : np.readUtf8String());
      }
      var rows = [];
      while (step(st) === 100) {
        var o = {};
        for (var j = 0; j < n; j++) {
          var tp = ctext(st, j);
          if (tp.isNull()) { o[cols[j]] = null; continue; }
          try { o[cols[j]] = tp.readUtf8String(); } catch (x) { o[cols[j]] = null; }
        }
        rows.push(o);
      }
      fin(st);
      return { cols: cols, rows: rows };
    }

    function writeFile(path, str) {
      try { var f = new File(path, 'w'); f.write(str); f.close(); return true; }
      catch (x) { console.log('[bulk] 写文件失败 ' + path + ' : ' + x); return false; }
    }

    var seen = {};
    Interceptor.attach(e.sqlite3_step, {
      onEnter: function (a) {
        try {
          var db = dbh(a[0]);
          if (db.isNull()) return;
          var key = db.toString();
          if (seen[key]) return;

          // 只认有 chatters 表的库(contact.db)
          var chk = query(db, 'SELECT count(*) AS n FROM chatters');
          if (!chk || !chk.rows.length) return;
          seen[key] = 1;

          var fn = '';
          try { var fp = fname(db, Memory.allocUtf8String('main')); if (!fp.isNull()) fn = fp.readUtf8String(); } catch (x) {}
          var tid = '';
          var tq = query(db, "SELECT tenant_id FROM chatters WHERE tenant_id IS NOT NULL AND tenant_id<>'' LIMIT 1");
          if (tq && tq.rows.length) tid = tq.rows[0].tenant_id || '';

          // —— 侦察: 表清单 + profile/部门/上级/工号 相关表的建表语句 ——
          var tables = [];
          var tl = query(db, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
          if (tl) tables = tl.rows.map(function (r) { return r.name; });

          var schema = {};
          var sc = query(db,
            "SELECT name, sql FROM sqlite_master WHERE type='table' AND (" +
            "name LIKE '%profile%' OR name LIKE '%chatter%' OR name LIKE '%depart%' OR " +
            "name LIKE '%dept%' OR name LIKE '%leader%' OR name LIKE '%employee%' OR name LIKE '%org%')");
          if (sc) sc.rows.forEach(function (r) { schema[r.name] = r.sql; });

          // —— 正菜: 批量 dump chatter_profiles 全表 ——
          var prof = query(db, DUMP_SQL);

          var out = {
            file: fn,
            tenant_id: tid,
            table_count: tables.length,
            tables: tables,
            profile_schema: schema,
            profile_cols: prof ? prof.cols : null,
            profile_rows: prof ? prof.rows : 'NO_TABLE_or_QUERY_FAIL'
          };

          var tag = tid || key.slice(-8);
          var path = OUT_DIR + '/profiles_bulk_' + tag + '.json';
          writeFile(path, JSON.stringify(out, null, 1));

          console.log('[bulk] contact.db=' + fn + ' tenant=' + tid +
                      ' 表数=' + tables.length +
                      ' profile列=' + (prof ? prof.cols.length : 0) +
                      ' profile行=' + (prof ? prof.rows.length : 'N/A') +
                      ' -> ' + path);
          console.log('[bulk] 相关表建表语句: ' + JSON.stringify(Object.keys(schema)));
          if (prof && prof.cols) console.log('[bulk] chatter_profiles 列名: ' + prof.cols.join(', '));
          send({ ev: 'BULK', file: fn, tenant: tid, cols: prof ? prof.cols : null,
                 rows: prof ? prof.rows.length : 0, tables: tables });
        } catch (err) { console.log('[bulk] err ' + err); }
      }
    });

    console.log('[bulk] armed —— 现在进飞书【通讯录→组织架构】上下滑一圈触发');
    send({ ev: 'armed' });
  });
})();
