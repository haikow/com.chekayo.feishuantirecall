(function(){
function getExp(m,s){var x=Process.findModuleByName(m);if(!x)return null;try{return x.getExportByName(s)}catch(e){try{return x.findExportByName(s)}catch(e2){return null}}}
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  send({ev:'liblark_base', v:l.base.toString(16)});
  var st=setInterval(function(){
    var step=getExp('libsqlcipher.so','sqlite3_step'); var sqlp=getExp('libsqlcipher.so','sqlite3_sql');
    if(step&&sqlp){clearInterval(st);
      var sqlf=new NativeFunction(sqlp,'pointer',['pointer']);
      var cnt=0, seen={};
      Interceptor.attach(step,{onEnter:function(a){
        try{var p=sqlf(a[0]);if(p.isNull())return;var s=p.readCString();if(!s)return;cnt++;
          if(s.indexOf('me_read')>=0||s.indexOf('read_position')>=0||s.indexOf('message_read')>=0){send({ev:'READSQL', sql:s.substring(0,100)});}
          // sample distinct write statements to prove liveness
          var head=s.substring(0,40);
          if((s.indexOf('UPDATE')>=0||s.indexOf('REPLACE')>=0||s.indexOf('INSERT')>=0)&&!seen[head]){seen[head]=1;if(Object.keys(seen).length<=40)send({ev:'W', sql:head});}
        }catch(e){}
      }});
      setInterval(function(){send({ev:'HB', steps:cnt});},5000);
      send({ev:'sqlite_hooked_v2'});
    }
  },200);
});
})();
