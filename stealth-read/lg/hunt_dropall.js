(function(){
var OFF_ENQ = 0x65862d4;   // frontier terminal enqueue (7.71.8)
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  var base=l.base;
  send({ev:'liblark_base', v:base.toString(16)});
  var enq=base.add(OFF_ENQ);
  var orig=new NativeFunction(enq,'pointer',['pointer','pointer','int','pointer']);
  var dropped=0;
  var enabled=false;   // 默认不丢, 收到 rpc 才开(避免一 attach 就影响登录)
  Interceptor.replace(enq, new NativeCallback(function(a1,a2,a3,a4){
    if(enabled){ dropped++; return ptr(0); }
    return orig(a1,a2,a3,a4);
  },'pointer',['pointer','pointer','int','pointer']));
  send({ev:'dropall_armed_waiting'});
  setTimeout(function(){ enabled=true; send({ev:'DROPALL_ON'}); }, 5000);  // 5s 后开始丢光
  setInterval(function(){send({ev:'STAT', enabled:enabled, dropped:dropped});},3000);
});
})();
