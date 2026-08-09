(function(){
function getExp(m,s){var x=Process.findModuleByName(m);if(!x)return null;try{return x.getExportByName(s)}catch(e){try{return x.findExportByName(s)}catch(e2){return null}}}
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>200){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  var base=l.base, lo=base, hi=base.add(l.size);
  send({ev:'liblark_base', v:base.toString(16), size:l.size});
  function inLark(p){try{return p.compare(lo)>=0 && p.compare(hi)<0}catch(e){return false}}
  var st=setInterval(function(){
    var step=getExp('libsqlcipher.so','sqlite3_step');
    var sqlp=getExp('libsqlcipher.so','sqlite3_sql');
    if(step&&sqlp){clearInterval(st);
      var sqlf=new NativeFunction(sqlp,'pointer',['pointer']);
      var following=false, rounds=0;
      var acc={};
      Interceptor.attach(step,{onEnter:function(a){
        try{var p=sqlf(a[0]);if(p.isNull())return;var s=p.readCString();if(!s)return;
          // 只用 read_position 写作为一次读的干净触发, 且限次数
          if(s.indexOf('UPDATE `chats` SET `read_position`')===0 && !following && rounds<6){
            following=true; rounds++;
            var tid=this.threadId;
            Stalker.follow(tid,{events:{call:true},
              onCallSummary:function(summary){
                for(var t in summary){var tp=ptr(t); if(inLark(tp)){var off=tp.sub(base).toString(16); acc[off]=(acc[off]||0)+summary[t];}}
              }});
            setTimeout(function(){
              try{Stalker.unfollow(tid);}catch(e){}
              Stalker.flush();
              setTimeout(function(){
                var arr=[]; for(var k in acc) arr.push([k,acc[k]]);
                arr.sort(function(a,b){return a[1]-b[1]});
                send({ev:'STALK', round:rounds, n:arr.length, targets:arr});
                acc={}; following=false;
              },120);
            },45);
          }
        }catch(e){}
      }});
      send({ev:'hunt_stalk_armed'});
    }
  },200);
});
})();
