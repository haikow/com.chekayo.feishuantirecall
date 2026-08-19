(function(){
function getExp(m,s){var x=Process.findModuleByName(m);return x?x.getExportByName(s):null}
function whenSql(cb){var m=Process.findModuleByName('libsqlcipher.so');if(m){cb(m);return}var t=setInterval(function(){var mm=Process.findModuleByName('libsqlcipher.so');if(mm){clearInterval(t);cb(mm)}},100)}
whenSql(function(m){
  var e={};
  ['sqlite3_prepare_v2','sqlite3_step','sqlite3_finalize','sqlite3_db_handle','sqlite3_column_int64','sqlite3_column_text','sqlite3_column_bytes','sqlite3_column_blob','sqlite3_column_count','sqlite3_sql'].forEach(function(n){e[n]=m.getExportByName(n)});
  var prep=new NativeFunction(e.sqlite3_prepare_v2,'int',['pointer','pointer','int','pointer','pointer']);
  var step=new NativeFunction(e.sqlite3_step,'int',['pointer']);
  var fin=new NativeFunction(e.sqlite3_finalize,'int',['pointer']);
  var dbh=new NativeFunction(e.sqlite3_db_handle,'pointer',['pointer']);
  var ci64=new NativeFunction(e.sqlite3_column_int64,'int64',['pointer','int']);
  var ctext=new NativeFunction(e.sqlite3_column_text,'pointer',['pointer','int']);
  var cbytes=new NativeFunction(e.sqlite3_column_bytes,'int',['pointer','int']);
  var cblob=new NativeFunction(e.sqlite3_column_blob,'pointer',['pointer','int']);
  var sqlf=new NativeFunction(e.sqlite3_sql,'pointer',['pointer']);
  var msgDb=null, pending=false, busy=false;
  function hx(p,n){try{var a=new Uint8Array(p.readByteArray(n));var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s}catch(e){return''}}
  function asc(p,n){try{var a=new Uint8Array(p.readByteArray(n));var s='';for(var i=0;i<a.length;i++){var b=a[i];s+=(b>=32&&b<127)?String.fromCharCode(b):'.'}return s}catch(e){return''}}
  function runQuery(db){
    var sql="SELECT id, chat_id, from_id, type_, me_read, create_time, content FROM messages WHERE me_read=0 ORDER BY create_time DESC LIMIT 6";
    var ppStmt=Memory.alloc(Process.pointerSize);
    var csql=Memory.allocUtf8String(sql);
    var rc=prep(db, csql, -1, ppStmt, ptr(0));
    if(rc!==0){send({ev:'QERR', rc:rc}); return;}
    var stmt=ppStmt.readPointer();
    var rows=0;
    while(step(stmt)===100){
      rows++;
      var id=ci64(stmt,0).toString();
      var chat=ci64(stmt,1).toString();
      var from=ci64(stmt,2).toString();
      var typ=ci64(stmt,3).toString();
      var ct=ci64(stmt,5).toString();
      var n=cbytes(stmt,6); var bp=cblob(stmt,6);
      send({ev:'UNREAD', id:id, chat:chat, from:from, type:typ, ctime:ct, clen:n,
            chex:hx(bp, Math.min(n,220)), casc:asc(bp, Math.min(n,220))});
    }
    fin(stmt);
    send({ev:'QDONE', rows:rows});
  }
  Interceptor.attach(e.sqlite3_step,{onEnter:function(a){
    if(busy) return;
    try{
      var p=sqlf(a[0]); if(p.isNull()) return; var s=p.readCString(); if(!s) return;
      if(s.indexOf('messages')>=0){
        if(!msgDb){ msgDb=dbh(a[0]); send({ev:'msgdb', h:msgDb.toString()}); }
        if(pending && msgDb){ pending=false; busy=true; try{ runQuery(dbh(a[0])); }catch(err){ send({ev:'RUNERR', e:String(err)}); } busy=false; }
      }
    }catch(e2){}
  }});
  send({ev:'peek_armed'});
  // 每 4s 触发一次查询(等下一条 messages 语句在正确线程上执行)
  setInterval(function(){ pending=true; }, 4000);
});
})();
