// probe3: hook im.message.put_read 处理器 sub_5BE3A64, 找原始 messageId 列表(建包上游).
(function () {
  const OFF_PUTREAD = 0x5BE3A64;
  const OUT = "/data/data/com.ss.android.lark/aapro_out3.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[AAPRO3] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe3 start ====");
  let m=Process.findModuleByName("liblark.so"); if(!m){log("no base");return;}
  const base=m.base; log("base="+base);

  function looksId(v){try{const hi=v.shr(56).and(0xff).toNumber();return hi>=0x50&&hi<=0x70;}catch(e){return false;}}
  // 递归找 snowflake id: 在 p 指向的内存里扫 qwords 个 u64, 命中像id的就报, 并对像指针的下探一层
  function scan(name,p,qwords,depth){
    if(p.isNull()||depth<0)return;
    let found=[];
    for(let i=0;i<qwords;i++){
      let v;try{v=p.add(i*8).readU64();}catch(e){break;}
      let vp=ptr(v);
      if(looksId(vp)) found.push("+0x"+(i*8).toString(16)+"=0x"+v.toString(16));
      // 像堆指针则下探(找 Vec<id> 缓冲)
      if(depth>0){
        let hi=vp.shr(40).toNumber();
        if(hi>=0x77 && hi<=0x79){ // 用户空间堆
          try{ let inner=[]; for(let j=0;j<8;j++){let iv=vp.add(j*8).readU64();if(looksId(ptr(iv)))inner.push("+0x"+(j*8).toString(16)+"=0x"+iv.toString(16));}
               if(inner.length>=2) log("  "+name+"["+i+"]->"+vp+" 内含多个ID: "+inner.join(" ")); }catch(e){}
        }
      }
    }
    if(found.length) log("  "+name+" 直含ID: "+found.join(" "));
  }

  let hits=0;
  Interceptor.attach(base.add(OFF_PUTREAD),{
    onEnter(args){
      hits++; if(hits>4)return;
      log("#### put_read 命中 #"+hits+" tid="+this.threadId+" ####");
      for(let i=0;i<6;i++){let r=this.context["x"+i];log("  x"+i+"="+r); scan("x"+i,r,20,1);}
    }
  });
  log("hook put_read 挂好, 请开未读聊天");
})();
