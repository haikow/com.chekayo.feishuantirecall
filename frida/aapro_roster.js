// aapro_roster.js —— 算法助手Pro frida 脚本:全量组织通讯录 dump
//
// 内核同 profiles_bulk.js / resign_check.js:hook 进程内 libsqlcipher,借飞书已解密的
// contact.db 活句柄就地跑 SELECT。区别:
//   1) 查【全部】chatters(不再只 is_resigned=1),按 tenant_id 分组;
//   2) 增量累积:进程内维护 id->row 的 Map,每次飞书查 contact.db 就重新全量 SELECT 并 merge,
//      再把合并结果整体写文件。配合"组织架构巡游"逐部门懒加载,最终 Map = 全公司花名册。
//   3) 同时 JOIN chatter_profiles_v3 富资料(部门/邮箱/工号,若存在)。
//
// 输出(飞书进程可写):
//   /data/data/com.ss.android.lark/files/aapro_roster.json      —— 全量花名册(去重)
//   /data/data/com.ss.android.lark/files/aapro_roster.log       —— 运行日志
// adb 读取: adb shell "su -c 'cat /data/data/com.ss.android.lark/files/aapro_roster.json'"

(function () {
  var OUT_JSON = '/data/data/com.ss.android.lark/files/aapro_roster.json';
  var OUT_LOG  = '/data/data/com.ss.android.lark/files/aapro_roster.log';
  var PS = Process.pointerSize;

  var lf = null; try { lf = new File(OUT_LOG, 'a'); } catch (e) {}
  function log(s) {
    var l = '[ROSTER] ' + s;
    console.log(l);
    try { if (lf) { lf.write(l + '\n'); lf.flush(); } } catch (e) {}
  }
  log('==== roster script start ' + new Date().toString() + ' ====');

  // id -> 合并后的人员对象(跨多次触发累积、去重)
  var ROSTER = {};
  var lastDump = 0;
  var g_busy = false;   // 重入保护:我们自己调 prepare/step 会再次进 hook,靠它直接 bail

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
    ['sqlite3_prepare_v2','sqlite3_step','sqlite3_finalize','sqlite3_db_handle',
     'sqlite3_column_count','sqlite3_column_name','sqlite3_column_text',
     'sqlite3_db_filename'].forEach(function (n) { e[n] = m.getExportByName(n); });

    var prep   = new NativeFunction(e.sqlite3_prepare_v2,'int',['pointer','pointer','int','pointer','pointer']);
    var step   = new NativeFunction(e.sqlite3_step,'int',['pointer']);
    var fin    = new NativeFunction(e.sqlite3_finalize,'int',['pointer']);
    var dbh    = new NativeFunction(e.sqlite3_db_handle,'pointer',['pointer']);
    var ccount = new NativeFunction(e.sqlite3_column_count,'int',['pointer']);
    var cname  = new NativeFunction(e.sqlite3_column_name,'pointer',['pointer','int']);
    var ctext  = new NativeFunction(e.sqlite3_column_text,'pointer',['pointer','int']);
    var fname  = new NativeFunction(e.sqlite3_db_filename,'pointer',['pointer','pointer']);

    // 用飞书自己的 prepare(trampoline)跑我们的 SELECT,返回 {cols,rows}
    function query(db, sql) {
      var pp = Memory.alloc(PS);
      var cs = Memory.allocUtf8String(sql);
      if (prep(db, cs, -1, pp, ptr(0)) !== 0) return null;
      var st = pp.readPointer();
      var n = ccount(st);
      var cols = [];
      for (var i = 0; i < n; i++) { var np = cname(st,i); cols.push(np.isNull()?('c'+i):np.readUtf8String()); }
      var rows = [];
      while (step(st) === 100) {
        var o = {};
        for (var j = 0; j < n; j++) {
          var tp = ctext(st,j);
          if (tp.isNull()) { o[cols[j]] = null; continue; }
          try { o[cols[j]] = tp.readUtf8String(); } catch (x) { o[cols[j]] = null; }
        }
        rows.push(o);
      }
      fin(st);
      return { cols: cols, rows: rows };
    }

    function writeRoster() {
      // 按 tenant_id 分组统计
      var byTenant = {};
      var ids = Object.keys(ROSTER);
      for (var i = 0; i < ids.length; i++) {
        var t = ROSTER[ids[i]].tenant_id || 'unknown';
        byTenant[t] = (byTenant[t] || 0) + 1;
      }
      var out = { updated: new Date().toString(), total: ids.length, by_tenant: byTenant,
                  people: ids.map(function (k) { return ROSTER[k]; }) };
      try { var f = new File(OUT_JSON, 'w'); f.write(JSON.stringify(out, null, 1)); f.close(); }
      catch (x) { log('写文件失败: ' + x); }
    }

    // 判断某表是否存在(轻量)
    function hasTable(db, name) {
      var r = query(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + name + "' LIMIT 1");
      return r && r.rows.length > 0;
    }

    var haveProfileV3 = null;   // 首次探测缓存

    function dumpAll(db) {
      // 主表:chatters 全量(不限 is_resigned)
      var q = query(db, "SELECT id, name, en_us_name, alias, another_name, tenant_id, " +
                        "is_resigned, is_frozen, is_registered, update_time FROM chatters");
      if (!q) return 0;
      var added = 0;
      for (var i = 0; i < q.rows.length; i++) {
        var r = q.rows[i];
        if (!r.id) continue;
        if (!ROSTER[r.id]) added++;
        // merge(后到的补空字段)
        var cur = ROSTER[r.id] || {};
        for (var k in r) if (r[k] != null && r[k] !== '') cur[k] = r[k];
        ROSTER[r.id] = cur;
      }
      // 富资料 v3(部门/邮箱/工号,blob 存 hex 备解)
      if (haveProfileV3 === null) haveProfileV3 = hasTable(db, 'chatter_profiles_v3');
      if (haveProfileV3) {
        // 连 blob 一起掏:hex(profile) 走 sqlite 内建 hex(),经 column_text 取回十六进制串
        var p = query(db, "SELECT chatter_id, hex(profile) AS ph FROM chatter_profiles_v3");
        if (p) for (var j = 0; j < p.rows.length; j++) {
          var cid = p.rows[j].chatter_id;
          if (cid && ROSTER[cid]) {
            ROSTER[cid].has_profile = 1;
            if (p.rows[j].ph) ROSTER[cid].profile_hex = p.rows[j].ph;
          }
        }
      }
      return added;
    }

    // 改 hook sqlite3_prepare_v2:每条语句只触发一次(远低于 step 频率),arg0 直接是 db 句柄。
    // 关键:g_busy 重入保护 —— 我们在 onEnter 里调 query()(内部 prepare/step 都是被 hook 的函数),
    // 会再次进本 onEnter,此时 g_busy=true 直接返回,原函数照常执行 → 不递归、不崩。
    var seenChatters = {};   // 认库缓存:哪些 db 句柄已确认是 contact.db(1=是,0=否)
    Interceptor.attach(e.sqlite3_prepare_v2, {
      onEnter: function (a) {
        if (g_busy) return;                         // 重入直接 bail(我们自己的 prepare 调用)
        var db = a[0];
        if (db.isNull()) return;
        var key = db.toString();
        if (seenChatters[key] === 0) return;        // 已确认不是 contact.db
        g_busy = true;
        try {
          if (seenChatters[key] === undefined) {
            var chk = query(db, 'SELECT count(*) AS n FROM chatters');
            if (!chk || !chk.rows.length) { seenChatters[key] = 0; return; }  // 没 chatters 表
            seenChatters[key] = 1;
            var fn = ''; try { var fp = fname(db, Memory.allocUtf8String('main')); if (!fp.isNull()) fn = fp.readUtf8String(); } catch (x) {}
            log('认到 contact.db 句柄=' + key + ' file=' + fn + ' 现有chatters=' + chk.rows[0].n);
          }
          var now = Date.now();
          if (now - lastDump < 1200) return;         // 节流:最多每 1.2s 全量 dump 一次
          lastDump = now;
          var added = dumpAll(db);
          writeRoster();
          if (added > 0) log('累计 ' + Object.keys(ROSTER).length + ' 人 (+' + added + ')');
        } catch (err) {
          log('onEnter err ' + err);
        } finally {
          g_busy = false;
        }
      }
    });

    log('armed(prepare_v2 + 重入保护)—— 进飞书【通讯录→组织内联系人】逐部门滑动即可增量累积');
  });
})();
