// probe11 诊断: 统计 EVP_AEAD_CTX_seal 全部调用 + 熵分布, 区分"没调用"vs"全密文".
(function () {
  const OUT = "/data/data/com.ss.android.lark/aapro_out11.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[P11] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe11 diag start ====");
  let seal = Module.findExportByName(null,"EVP_AEAD_CTX_seal");
  if(!seal){ log("no seal export"); return; }
  function entropy(u){let c=new Array(256).fill(0);for(const b of u)c[b]++;let n=u.length,h=0;for(const x of c){if(x){let p=x/n;h-=p*Math.log2(p);}}return h;}
  let total=0, lowcnt=0, minEnt=9, minSample=null;
  // 熵桶: <5, 5-6, 6-7, 7-7.5, >7.5
  let bucket=[0,0,0,0,0];
  Interceptor.attach(seal,{ onEnter(a){
    let inlen=a[7].toInt32(); if(inlen<8||inlen>65536)return;
    let b; try{b=new Uint8Array(a[6].readByteArray(Math.min(inlen,128)));}catch(e){return;}
    let e=entropy(b); total++;
    if(e<5)bucket[0]++; else if(e<6)bucket[1]++; else if(e<7)bucket[2]++; else if(e<7.5)bucket[3]++; else bucket[4]++;
    if(e<minEnt){ minEnt=e; let asc=[...b].map(x=>(x>=32&&x<127)?String.fromCharCode(x):'.').join(''); minSample="len="+inlen+" ent="+e.toFixed(2)+" asc="+asc; }
    if(e<6.3){ lowcnt++; let asc=[...b].map(x=>(x>=32&&x<127)?String.fromCharCode(x):'.').join(''); log("LOW#"+lowcnt+" len="+inlen+" ent="+e.toFixed(2)+" asc="+asc); }
    if(total%40===0) log("STAT total="+total+" 熵桶[<5,5-6,6-7,7-7.5,>7.5]="+JSON.stringify(bucket)+" 最低熵样本: "+minSample);
  }});
  log("hook 挂好(诊断). 打开未读聊天, 我看熵分布");
})();
