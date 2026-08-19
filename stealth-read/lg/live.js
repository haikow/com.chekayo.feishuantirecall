(function(){
function getExp(m,s){var x=Process.findModuleByName(m);if(!x)return null;try{return x.getExportByName(s)}catch(e){try{return x.findExportByName(s)}catch(e2){return null}}}
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  send({ev:'liblark_base', v:l.base.toString(16)});
  var st=setInterval(function(){
    var step=getExp('libsqlcipher.so','sqlite3_step'); var sqlp=getExp('libsqlcipher.so','sqlite3_sql');
    if(step&&sqlp){clearInterval(st);
      var sqlf=new NativeFunction(sqlp,'pointer',['pointer']);
      Interceptor.attach(step,{onEnter:function(a){
        try{var p=sqlf(a[0]);if(p.isNull())return;var s=p.readCString();
          if(s&&(s.indexOf('me_read')>=0||s.indexOf('read_position')>=0)) send({ev:'READSQL', sql:s.substring(0,90)});
        }catch(e){}
      }});
      send({ev:'sqlite_hooked'});
    }
  },200);
});
})();
