(function(){
var OFF_ENQ = 0x65862d4;              // frontier terminal enqueue (ported from 7.69.6 sub_649FE5C, exact body match)
var CHAT = "7000000000000000000";     // test chat_id anchor (read target)
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
function bhex(p,n){try{var a=new Uint8Array(p.readByteArray(n));var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s}catch(e){return''}}
function asciiHex(dec){var s='';for(var i=0;i<dec.length;i++)s+=dec.charCodeAt(i).toString(16);return s}
// find runs of ascii decimal digits >=15 chars in a hex blob (ids sent in clear)
function digitsIn(hex){var out=[];var cur='';for(var i=0;i+1<hex.length;i+=2){var b=parseInt(hex.substr(i,2),16);if(b>=0x30&&b<=0x39){cur+=String.fromCharCode(b)}else{if(cur.length>=15)out.push(cur);cur=''}}if(cur.length>=15)out.push(cur);return out}
whenLark(function(l){
  var base=l.base;
  send({ev:'liblark_base', v:base.toString(16)});
  var enq=base.add(OFF_ENQ);
  var chatAscii=asciiHex(CHAT);
  var seen=0;
  Interceptor.attach(enq,{
    onEnter:function(a){
      try{
        var a3=a[2].toInt32()&0xff;
        var a4=a[3];
        var buf=''; try{buf=bhex(a4.add(8).readPointer(),320)}catch(e){}
        var head=bhex(a4,64);
        var blob=buf+head;
        var digs=digitsIn(blob);
        var hasChat=blob.indexOf(chatAscii)>=0;
        if(hasChat || digs.length>0){
          seen++;
          if(seen<=60) send({ev:'ENQ', a3:a3, chat:hasChat?1:0, ids:digs.slice(0,6), blob:buf.substring(0,160)});
        }
      }catch(e){}
    }
  });
  send({ev:'hunt_enq_armed', off:'0x'+OFF_ENQ.toString(16)});
});
})();
