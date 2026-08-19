// probe12: 四个加密原语一起挂, 看 frontier 到底走哪个. 都不响=Rust ring/自定义加密.
(function () {
  const OUT="/data/data/com.ss.android.lark/aapro_out12.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[P12] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe12 crypto-primitive census ====");
  function ent(u){let c=new Array(256).fill(0);for(const b of u)c[b]++;let n=u.length,h=0;for(const x of c){if(x){let p=x/n;h-=p*Math.log2(p);}}return h;}
  let cnt={};
  // name -> {addr, inArgIdx, lenArgIdx}
  const T={
    "EVP_AEAD_CTX_seal":[6,7],
    "EVP_EncryptUpdate":[3,4],
    "EVP_CipherUpdate":[3,4],
    "BIO_write":[1,2],
  };
  for(const name in T){
    let ad=Module.findExportByName(null,name);
    if(!ad){ log("(no export) "+name); continue; }
    cnt[name]=0;
    let [ii,li]=T[name];
    Interceptor.attach(ad,{ onEnter(a){
      let ln=a[li].toInt32(); if(ln<8||ln>65536)return;
      cnt[name]++;
      if(cnt[name]<=6){ let b;try{b=new Uint8Array(a[ii].readByteArray(Math.min(ln,96)));}catch(e){return;}
        let e=ent(b), asc=[...b].map(x=>(x>=32&&x<127)?String.fromCharCode(x):'.').join('');
        log(name+" #"+cnt[name]+" len="+ln+" ent="+e.toFixed(2)+(e<6.3?" <明文?>":"")+" asc="+asc); }
    }});
    log("hooked "+name+" @ "+ad);
  }
  // 每 3 秒打印计数
  let ticks=0;
  let iv=setInterval(function(){ ticks++; log("CENSUS t="+ticks*3+"s "+JSON.stringify(cnt)); if(ticks>=8)clearInterval(iv); },3000);
  log("census 开始, 请打开未读聊天并切几个会话");
})();
