// probe4: Stalker 前向追踪 send_success(sub_5B9A318) 每次执行调用的 liblark 函数,
//         找发送态里真正的建包/transmit(旧 gen_put_packets 0x5aec998 / builder 0x6111d8c / enqueue 0x649fe5c).
(function () {
  const OFF_SEND = 0x5B9A318;
  const OUT = "/data/data/com.ss.android.lark/aapro_out4.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[AAPRO4] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe4 start ====");
  let m=Process.findModuleByName("liblark.so"); if(!m){log("no base");return;}
  const base=m.base, lo=base, hi=base.add(0x9000000);
  log("base="+base);

  let hits=0;
  const seen = {};   // 全局去重的 callee 偏移 -> 命中次数
  Interceptor.attach(base.add(OFF_SEND),{
    onEnter(){
      hits++;
      if(hits>4){ return; }
      this._t=this.threadId; this._n=hits;
      const localHit = {};
      Stalker.follow(this.threadId,{
        events:{call:true},
        onCallSummary(summary){
          for(const tgt in summary){
            let a=ptr(tgt);
            if(a.compare(lo)>=0 && a.compare(hi)<0){
              let off="0x"+a.sub(base).toString(16);
              localHit[off]=(localHit[off]||0)+summary[tgt];
            }
          }
        }
      });
      this._local=localHit;
    },
    onLeave(){
      if(this._n && this._n<=4){
        try{Stalker.unfollow(this._t);}catch(e){}
        try{Stalker.flush();}catch(e){}
        // 打印这次执行调用到的 liblark 函数(按偏移)
        let offs=Object.keys(this._local||{});
        log("#### send poll #"+this._n+" tid="+this._t+" 调用了 "+offs.length+" 个 liblark 函数 ####");
        // 只列可能是发送/建包的(排除高频 tokio 执行器噪声): 全列, 后期人工筛
        log("  callees: "+offs.sort().join(" "));
      }
    }
  });
  log("Stalker hook 挂好, 请开未读聊天(只追前4次)");
})();
