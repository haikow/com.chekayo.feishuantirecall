(function(){
function whenSql(cb){var m=Process.findModuleByName('libsqlcipher.so');if(m){cb(m);return}var t=setInterval(function(){var mm=Process.findModuleByName('libsqlcipher.so');if(mm){clearInterval(t);cb(mm)}},100)}
whenSql(function(m){
  var e={};['sqlite3_prepare_v2','sqlite3_step','sqlite3_finalize','sqlite3_db_handle','sqlite3_column_int64','sqlite3_column_text','sqlite3_db_filename','sqlite3_sql'].forEach(function(n){e[n]=m.getExportByName(n)});
  var prep=new NativeFunction(e.sqlite3_prepare_v2,'int',['pointer','pointer','int','pointer','pointer']);
  var step=new NativeFunction(e.sqlite3_step,'int',['pointer']);
  var fin=new NativeFunction(e.sqlite3_finalize,'int',['pointer']);
  var dbh=new NativeFunction(e.sqlite3_db_handle,'pointer',['pointer']);
  var ci64=new NativeFunction(e.sqlite3_column_int64,'int64',['pointer','int']);
  var ctext=new NativeFunction(e.sqlite3_column_text,'pointer',['pointer','int']);
  var fname=new NativeFunction(e.sqlite3_db_filename,'pointer',['pointer','pointer']);
  var sqlf=new NativeFunction(e.sqlite3_sql,'pointer',['pointer']);
  function scalar(db,sql){var pp=Memory.alloc(Process.pointerSize);var cs=Memory.allocUtf8String(sql);if(prep(db,cs,-1,pp,ptr(0))!==0)return null;var st=pp.readPointer();var v=null;if(step(st)===100){v=ci64(st,0).valueOf();}fin(st);return v;}
  function scalarText(db,sql){var pp=Memory.alloc(Process.pointerSize);var cs=Memory.allocUtf8String(sql);if(prep(db,cs,-1,pp,ptr(0))!==0)return null;var st=pp.readPointer();var v=null;if(step(st)===100){var tp=ctext(st,0);if(!tp.isNull()){try{v=tp.readUtf8String();}catch(e){}}}fin(st);return v;}
  var seen={};
  Interceptor.attach(e.sqlite3_step,{onEnter:function(a){
    try{
      var db=dbh(a[0]); if(db.isNull()) return; var key=db.toString();
      if(seen[key]) return;
      // 只查有 chatters 表的库(contact.db)
      var total=scalar(db,"SELECT count(*) FROM chatters");
      if(total===null) return;   // 没这表, 不是 contact.db
      seen[key]=1;
      var fn=''; try{var fp=fname(db,Memory.allocUtf8String("main")); if(!fp.isNull()) fn=fp.readUtf8String();}catch(e){}
      var resigned=scalar(db,"SELECT count(*) FROM chatters WHERE is_resigned=1");
      var frozen=scalar(db,"SELECT count(*) FROM chatters WHERE is_frozen=1");
      // 取几个 tenant_id 看是哪个公司
      var tid=scalarText(db,"SELECT tenant_id FROM chatters WHERE tenant_id IS NOT NULL AND tenant_id<>'' LIMIT 1");
      send({ev:'DB', file:fn, total:total, resigned:resigned, frozen:frozen, tenant:tid});
      // dump 离职名单
      if(resigned && resigned>0){
        var pp=Memory.alloc(Process.pointerSize);
        var cs=Memory.allocUtf8String("SELECT id,name,en_us_name,another_name,update_time FROM chatters WHERE is_resigned=1 ORDER BY update_time DESC");
        if(prep(db,cs,-1,pp,ptr(0))===0){
          var st=pp.readPointer(); var rows=[];
          while(step(st)===100){
            function t(i){var p=ctext(st,i);if(p.isNull())return'';try{return p.readUtf8String()||''}catch(e){return''}}
            rows.push({id:ci64(st,0).toString(), name:t(1), en:t(2), alias:t(3), ut:ci64(st,4).toString()});
          }
          fin(st);
          send({ev:'RESIGNED', rows:rows});
        }
      }
    }catch(e2){}
  }});
  send({ev:'armed'});
});
})();
