(function(){
var OFF_ENQ = 0x65862d4;
var CHAT = "7000000000000000000";
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
function hx(p,n){try{var a=new Uint8Array(p.readByteArray(n));var s='';for(var i=0;i<a.length;i++)s+=('0'+a[i].toString(16)).slice(-2);return s}catch(e){return''}}
function asciiHex(dec){var s='';for(var i=0;i<dec.length;i++)s+=dec.charCodeAt(i).toString(16);return s}
function safePtr(p){try{return p.readPointer()}catch(e){return null}}
function digits(hex){var out=[];var cur='';for(var i=0;i+1<hex.length;i+=2){var b=parseInt(hex.substr(i,2),16);if(b>=0x30&&b<=0x39){cur+=String.fromCharCode(b)}else{if(cur.length>=12)out.push(cur);cur=''}}if(cur.length>=12)out.push(cur);return out}
whenLark(function(l){
  var base=l.base, lo=base, hi=base.add(l.size);
  send({ev:'liblark_base', v:base.toString(16)});
  function heap(p){try{return p && p.compare(ptr('0x1000'))>0 && (p.compare(lo)<0||p.compare(hi)>=0)}catch(e){return false}}
  var enq=base.add(OFF_ENQ);
  var chatAscii=asciiHex(CHAT);
  var total=0, logged=0;
  Interceptor.attach(enq,{onEnter:function(a){
    total++;
    try{
      var a4=a[3];
      var v8=safePtr(a4.add(8)); if(!v8) return;
      var b8=hx(v8,320);
      // 跟进 b8 前 6 个 8 字节槽里的堆指针, 各挖 160 字节
      var deep='';
      for(var off=0; off<48; off+=8){
        var pp=safePtr(v8.add(off));
        if(heap(pp)) deep+=hx(pp,160);
      }
      var all=b8+deep;
      var isChat=all.indexOf(chatAscii)>=0;
      var digs=digits(all);
      if(!isChat && digs.length===0) return;   // 只记含 id 的相关包(跳过空闲噪声)
      if(logged>=200) return;
      logged++;
      var a3=a[2].toInt32()&0xff;
      send({ev:'FP', a3:a3, chat:isChat?1:0, ids:digs.slice(0,5), b8:b8.substring(0,120), deeplen:deep.length});
    }catch(e){}
  }});
  send({ev:'fp_armed'});
  setInterval(function(){send({ev:'TOT', total:total, logged:logged});},4000);
});
})();
