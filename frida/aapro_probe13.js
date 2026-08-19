// probe13: Java 层验证——主 app"打开聊天"是否在 Java 构造 PutReadMessagesRequest.
// hook 构造函数 + Wire ADAPTER.encode. 触发=Java层可拦(清 message_ids);不触发=native建pb.
(function () {
  const OUT="/data/data/com.ss.android.lark/aapro_out13.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[P13] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== probe13 java start ====");
  if(!Java || !Java.available){ log("Java 不可用!"); return; }
  Java.perform(function(){
    try{
      var C=Java.use("com.ss.android.lark.pb.messages.PutReadMessagesRequest");
      var Ex=Java.use("java.lang.Exception");
      var Log=Java.use("android.util.Log");
      var hitCtor=0;
      C.$init.overloads.forEach(function(ov){
        ov.implementation=function(){
          hitCtor++;
          var mids="?";
          try{ for(var i=0;i<arguments.length;i++){ if(arguments[i]&&arguments[i].toString&&/^\[.*\]$/.test(""+arguments[i])){ mids=""+arguments[i]; break; } } }catch(e){}
          log("CTOR #"+hitCtor+" argc="+arguments.length+" listArg="+mids);
          if(hitCtor<=4){ try{ log("  STACK:\n"+Log.getStackTraceString(Ex.$new())); }catch(e){} }
          return ov.apply(this,arguments);
        };
      });
      log("已 hook PutReadMessagesRequest 的 "+C.$init.overloads.length+" 个构造函数");
      // 也 hook ADAPTER.encode(序列化咽喉)
      try{
        var B=Java.use("com.ss.android.lark.pb.messages.PutReadMessagesRequest$b");
        var he=0;
        B.encode.overloads.forEach(function(ov){ try{ ov.implementation=function(){ he++; if(he<=6) log("ENCODE #"+he+" args="+arguments.length); return ov.apply(this,arguments);}; }catch(e){} });
        log("已 hook encode");
      }catch(e){ log("encode hook 失败(可能内部类名不同): "+e); }
      log("就绪. 现在主 app 打开一个【未读】聊天, 我看 CTOR/ENCODE 是否触发");
    }catch(e){ log("hook 异常: "+e); }
  });
})();
