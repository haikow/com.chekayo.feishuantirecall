// probe9: 换思路——不信 IDA 静态锚点, 直接抓 native 传输出口 SSL_write.
// 1) 枚举各模块的 TLS/crypto 导出符号(看 BoringSSL 符号在不在/在哪个 so)
// 2) 找到 SSL_write 就 hook, dump 出站首段 + 算熵(判明文 protobuf vs 密文)
(function () {
  const OUT = "/data/data/com.ss.android.lark/aapro_out9.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[P9] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe9 start ====");

  const SYMS = ["SSL_write","SSL_read","SSL_write_ex","BIO_write",
                "EVP_EncryptUpdate","EVP_AEAD_CTX_seal","EVP_CipherUpdate"];
  // 枚举: 每个符号在哪个模块导出
  let found = {};
  for (const s of SYMS) {
    let a = null;
    try { a = Module.findExportByName(null, s); } catch(e){}
    if (a) { found[s] = a; log("EXPORT "+s+" @ "+a); }
    else log("(no export) "+s);
  }
  // 也扫 liblark 内部符号(静态链接可能不导出, 用 enumerateSymbols)
  try {
    let m = Process.findModuleByName("liblark.so");
    if (m) {
      let syms = m.enumerateSymbols().filter(x => /SSL_write|SSL_read|EVP_AEAD.*seal|aead.*seal/i.test(x.name));
      log("liblark 内部匹配符号数="+syms.length);
      syms.slice(0,10).forEach(x=>log("  SYM "+x.name+" @ "+x.address));
      if (!found["SSL_write"]) {
        let w = syms.find(x=>/^SSL_write$/.test(x.name));
        if (w) { found["SSL_write"] = w.address; log("用内部符号 SSL_write @ "+w.address); }
      }
    }
  } catch(e){ log("enumerateSymbols 失败: "+e); }

  function entropy(bytes){ // 0..8
    let c=new Array(256).fill(0); for(const b of bytes) c[b]++;
    let n=bytes.length, h=0; for(const x of c){ if(x){ let p=x/n; h-=p*Math.log2(p); } } return h;
  }
  function hexdump(ptr,n){ try{ let b=ptr.readByteArray(n); let u=new Uint8Array(b);
    let hex=[...u].map(x=>x.toString(16).padStart(2,'0')).join(' ');
    let asc=[...u].map(x=>(x>=32&&x<127)?String.fromCharCode(x):'.').join('');
    return {hex,asc,ent:entropy(u)}; }catch(e){return null;} }

  if (found["SSL_write"]) {
    let hits=0;
    // SSL_write(ssl, buf, num) -> x1=buf, x2=num
    Interceptor.attach(found["SSL_write"], { onEnter(a){
      hits++; if(hits>30) return;
      let buf=a[1], num=a[2].toInt32();
      if (num<=0||num>100000) return;
      let d=hexdump(buf, Math.min(num,64));
      if(!d) return;
      log("SSL_write #"+hits+" len="+num+" ent="+d.ent.toFixed(2)+
          (d.ent<6.0?"  <<< 低熵=疑似明文!":"")+
          "\n   asc: "+d.asc+"\n   hex: "+d.hex);
    }});
    log("SSL_write hook 挂好, 请打开一个【有未读】的聊天. ent<6=明文 ent>7.5=密文");
  } else {
    log("!! 没找到 SSL_write. 结论: liblark 静态链 BoringSSL 且剥符号, 此路需换 EVP_AEAD_seal 或按字节特征扫.");
  }
})();
