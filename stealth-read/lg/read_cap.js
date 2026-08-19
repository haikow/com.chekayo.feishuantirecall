(function(){
function getExp(m,s){var x=Process.findModuleByName(m);if(!x)return null;try{return x.getExportByName(s)}catch(e){try{return x.findExportByName(s)}catch(e2){return null}}}
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  send({ev:'liblark_base', v:l.base.toString(16)});
  var st=setInterval(function(){
    var step=getExp('libsqlcipher.so','sqlite3_step');
    var sqlp=getExp('libsqlcipher.so','sqlite3_sql');
    var esqlp=getExp('libsqlcipher.so','sqlite3_expanded_sql');
    if(step&&sqlp&&esqlp){clearInterval(st);
      var sqlf=new NativeFunction(sqlp,'pointer',['pointer']);
      var esqlf=new NativeFunction(esqlp,'pointer',['pointer']);
      Interceptor.attach(step,{onEnter:function(a){
        try{var p=sqlf(a[0]);if(p.isNull())return;var s=p.readCString();if(!s)return;
          if(s.indexOf('me_read')>=0||s.indexOf('read_position')>=0||s.indexOf('message_read')>=0||s.indexOf('read_time')>=0){
            var es=''; try{var ep=esqlf(a[0]); if(!ep.isNull()) es=ep.readCString();}catch(e){}
            send({ev:'READ', tid:this.threadId, sql:(es||s).substring(0,220)});
          }
        }catch(e){}
      }});
      send({ev:'read_cap_armed'});
    }
  },200);
});
})();
