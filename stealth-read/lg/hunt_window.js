(function(){
var OFF_ENQ = 0x65862d4;      // frontier terminal enqueue (7.71.8)
var WINDOW = 4000;            // ms: after a read-write, drop ALL frontier enqueues this long
function getExp(m,s){var x=Process.findModuleByName(m);if(!x)return null;try{return x.getExportByName(s)}catch(e){try{return x.findExportByName(s)}catch(e2){return null}}}
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  var base=l.base;
  send({ev:'liblark_base', v:base.toString(16)});
  var dropUntil=0, dropped=0, passed=0, reads=0;
  // 1) frontier enqueue replace: drop everything during the post-read window
  var enq=base.add(OFF_ENQ);
  var orig=new NativeFunction(enq,'pointer',['pointer','pointer','int','pointer']);
  Interceptor.replace(enq, new NativeCallback(function(a1,a2,a3,a4){
    if(Date.now() < dropUntil){ dropped++; if(dropped<=50) send({ev:'DROP', a3:(a3&0xff), n:dropped}); return ptr(0); }
    passed++;
    return orig(a1,a2,a3,a4);
  },'pointer',['pointer','pointer','int','pointer']));
  // 2) read trigger via sqlite: open the drop window
  var st=setInterval(function(){
    var step=getExp('libsqlcipher.so','sqlite3_step'); var sqlp=getExp('libsqlcipher.so','sqlite3_sql');
    if(step&&sqlp){clearInterval(st);
      var sqlf=new NativeFunction(sqlp,'pointer',['pointer']);
      Interceptor.attach(step,{onEnter:function(a){
        try{var p=sqlf(a[0]);if(p.isNull())return;var s=p.readCString();if(!s)return;
          if(s.indexOf('UPDATE `chats` SET `read_position`')===0 || s.indexOf('UPDATE `messages` SET `me_read`')===0 || s.indexOf('REPLACE INTO `message_read_time`')===0 || (s.indexOf('UPDATE `chats_b`')===0 && s.indexOf('latest_read_timestamp')>=0)){
            dropUntil=Date.now()+WINDOW; reads++; send({ev:'READ_WINDOW', r:reads});
          }
        }catch(e){}
      }});
      send({ev:'hunt_window_armed', win:WINDOW});
    }
  },200);
  setInterval(function(){send({ev:'STAT', dropped:dropped, passed:passed, reads:reads});},5000);
});
})();
