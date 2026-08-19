(function(){
function whenSql(cb){var m=Process.findModuleByName('libsqlcipher.so');if(m){cb(m);return}var t=setInterval(function(){var mm=Process.findModuleByName('libsqlcipher.so');if(mm){clearInterval(t);cb(mm)}},100)}
whenSql(function(m){
  var e={};
  ['sqlite3_prepare_v2','sqlite3_step','sqlite3_finalize','sqlite3_db_handle','sqlite3_column_int64','sqlite3_column_text','sqlite3_column_bytes','sqlite3_column_blob','sqlite3_sql'].forEach(function(n){e[n]=m.getExportByName(n)});
  var prep=new NativeFunction(e.sqlite3_prepare_v2,'int',['pointer','pointer','int','pointer','pointer']);
  var step=new NativeFunction(e.sqlite3_step,'int',['pointer']);
  var fin=new NativeFunction(e.sqlite3_finalize,'int',['pointer']);
  var dbh=new NativeFunction(e.sqlite3_db_handle,'pointer',['pointer']);
  var ci64=new NativeFunction(e.sqlite3_column_int64,'int64',['pointer','int']);
  var ctext=new NativeFunction(e.sqlite3_column_text,'pointer',['pointer','int']);
  var cbytes=new NativeFunction(e.sqlite3_column_bytes,'int',['pointer','int']);
  var cblob=new NativeFunction(e.sqlite3_column_blob,'pointer',['pointer','int']);
  var sqlf=new NativeFunction(e.sqlite3_sql,'pointer',['pointer']);
  function hx(p,n){try{var a=new Uint8Array(p.readByteArray(n));var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s}catch(e){return''}}
  function prepOne(db,sql){var pp=Memory.alloc(Process.pointerSize);var cs=Memory.allocUtf8String(sql);if(prep(db,cs,-1,pp,ptr(0))!==0)return null;return pp.readPointer();}

  var fromIds=[], dumped=false, named=false, busy=false;

  function dumpMessages(db){
    var st=prepOne(db,"SELECT id, chat_id, from_id, type_, create_time, content FROM messages WHERE me_read=0 ORDER BY create_time DESC LIMIT 80");
    if(!st) return false;
    var out=[], chatIds={}, fset={};
    while(step(st)===100){
      var id=ci64(st,0).toString(), chat=ci64(st,1).toString(), from=ci64(st,2).toString();
      var typ=ci64(st,3).toString(), ct=ci64(st,4).toString();
      var n=cbytes(st,5); var bp=cblob(st,5);
      out.push({id:id,chat:chat,from:from,type:typ,ctime:ct,chex:hx(bp,Math.min(n,400))});
      chatIds[chat]=1; fset[from]=1;
    }
    fin(st);
    // chat 名(群名; p2p 常空) — 同库 chats 表
    var chatNames={};
    for(var c in chatIds){ var s2=prepOne(db,"SELECT `name` FROM `chats` WHERE `id`="+c+" LIMIT 1"); if(s2){ if(step(s2)===100){var tp=ctext(s2,0); if(!tp.isNull()){try{var nm=tp.readUtf8String(); if(nm) chatNames[c]=nm;}catch(e){}}} fin(s2);} }
    fromIds=Object.keys(fset);
    send({ev:'DUMP', count:out.length, rows:out, chatNames:chatNames});
    return true;
  }
  function resolveNames(db){
    if(!fromIds.length) return true;
    var names={}, got=0;
    for(var i=0;i<fromIds.length;i++){
      var st=prepOne(db,"SELECT `name`,`alias`,`another_name` FROM `chatters` WHERE `id`="+fromIds[i]+" LIMIT 1");
      if(!st) return false;  // 这个库没有 chatters(不是 contact.db)
      if(step(st)===100){
        var nm=null; var tp=ctext(st,0); if(!tp.isNull()){try{nm=tp.readUtf8String();}catch(e){}}
        var al=ctext(st,1); var alias=null; if(!al.isNull()){try{alias=al.readUtf8String();}catch(e){}}
        if(alias&&alias.length) nm=nm?(nm+'('+alias+')'):alias;
        if(nm){ names[fromIds[i]]=nm; got++; }
      }
      fin(st);
    }
    send({ev:'NAMES', names:names, got:got});
    return true;
  }

  var triedM={}, triedC={};
  Interceptor.attach(e.sqlite3_step,{onEnter:function(a){
    if(busy) return;
    try{
      var db=dbh(a[0]); if(db.isNull()) return; var key=db.toString();
      if(!dumped && !triedM[key]){ triedM[key]=1; busy=true; try{ if(dumpMessages(db)) dumped=true; }catch(err){} busy=false; return; }
      if(dumped && !named && !triedC[key]){ triedC[key]=1; busy=true; try{ if(resolveNames(db)) named=true; }catch(err){} busy=false; return; }
    }catch(e2){}
  }});
  send({ev:'armed'});
});
})();
