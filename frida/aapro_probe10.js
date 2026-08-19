// probe10: hook EVP_AEAD_CTX_seal(BoringSSL AEAD 加密). 明文=x6, len=x7.
// TLS 记录加密前的应用数据. 算熵判 明文protobuf vs 应用层密文.
// 低熵(<6.2)的出站明文全 dump, 找"打开未读聊天"瞬间的已读命令.
(function () {
  const OUT = "/data/data/com.ss.android.lark/aapro_out10.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[P10] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe10 start ====");
  let seal = Module.findExportByName(null,"EVP_AEAD_CTX_seal");
  if(!seal){ log("no EVP_AEAD_CTX_seal"); return; }
  log("seal @ "+seal);

  function entropy(u){ let c=new Array(256).fill(0); for(const b of u)c[b]++;
    let n=u.length,h=0; for(const x of c){if(x){let p=x/n;h-=p*Math.log2(p);}} return h; }

  let hits=0, shown=0;
  Interceptor.attach(seal,{ onEnter(a){
    let inp=a[6], inlen=a[7].toInt32();
    if(inlen<8 || inlen>8192) return;      // 已读命令是小包
    let b; try{ b=new Uint8Array(inp.readByteArray(Math.min(inlen,128))); }catch(e){return;}
    let ent=entropy(b);
    hits++;
    if(ent<6.3 && shown<60){                // 低熵=明文, 才 dump
      shown++;
      let asc=[...b].map(x=>(x>=32&&x<127)?String.fromCharCode(x):'.').join('');
      let hex=[...b.slice(0,48)].map(x=>x.toString(16).padStart(2,'0')).join(' ');
      log("SEAL#"+hits+" len="+inlen+" ent="+ent.toFixed(2)+" <明文?>\n   asc: "+asc+"\n   hex: "+hex);
    }
  }});
  log("hook 挂好. 现在打开一个【有未读】的聊天. (只 dump ent<6.3 的低熵出站)");
})();
