(function(){
var OFF_ENQ = 0x65862d4;              // frontier terminal enqueue (7.71.8)
var CHAT = "7000000000000000000";     // active read chat_id (drop read-reports referencing it)
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
function bhex(p,n){try{var a=new Uint8Array(p.readByteArray(n));var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s}catch(e){return''}}
function asciiHex(dec){var s='';for(var i=0;i<dec.length;i++)s+=dec.charCodeAt(i).toString(16);return s}
whenLark(function(l){
  var base=l.base;
  send({ev:'liblark_base', v:base.toString(16)});
  var enq=base.add(OFF_ENQ);
  var chatAscii=asciiHex(CHAT);
  var orig=new NativeFunction(enq,'pointer',['pointer','pointer','int','pointer']);
  var dropped=0, passed=0;
  Interceptor.replace(enq, new NativeCallback(function(a1,a2,a3,a4){
    try{
      var buf=''; try{buf=bhex(a4.add(8).readPointer(),320)}catch(e){}
      var head=bhex(a4,64);
      var blob=buf+head;
      if(blob.indexOf(chatAscii)>=0){
        dropped++;
        if(dropped<=40) send({ev:'DROP', a3:(a3&0xff), n:dropped});
        return ptr(0);   // caller ignores return; packet not enqueued
      }
    }catch(e){}
    passed++;
    return orig(a1,a2,a3,a4);
  },'pointer',['pointer','pointer','int','pointer']));
  send({ev:'hunt_drop_armed', off:'0x'+OFF_ENQ.toString(16)});
  setInterval(function(){send({ev:'STAT', dropped:dropped, passed:passed});},5000);
});
})();
