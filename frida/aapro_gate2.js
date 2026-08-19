// hook Resources.getText/getString, 加载"不在该群"资源时 dump 栈 -> 定位门控。
(function () {
  const OUT="/data/data/com.ss.android.lark/aapro_gate2.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[G2] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== gate2 start ====");
  if(!Java||!Java.available){log("Java 不可用");return;}
  Java.perform(function(){
    var Ex=Java.use("java.lang.Exception"), Log=Java.use("android.util.Log");
    function stk(){ try{return Log.getStackTraceString(Ex.$new());}catch(e){return "?";} }
    var TARGETS=[2131781919, 2131781737];   // 0x7f11791f, 0x7f117869
    function isTarget(id){ for(var i=0;i<TARGETS.length;i++) if(TARGETS[i]===id) return true; return false; }
    var R=Java.use("android.content.res.Resources");
    ["getText","getString"].forEach(function(mn){
      try{ R[mn].overloads.forEach(function(ov){
        // 只 hook 第一个参数是 int(资源id)的重载
        if(ov.argumentTypes.length>=1 && ov.argumentTypes[0].className==="int"){
          ov.implementation=function(){
            var id=arguments[0]; var r=ov.apply(this,arguments);
            try{ if(isTarget(id)){ log("★ getText/getString id=0x"+(id>>>0).toString(16)+" val="+r+"\n"+stk()); } }catch(e){}
            return r;
          };
        }
      }); log("hook Resources."+mn); }catch(e){log(mn+" 失败:"+e);}
    });
    // 兜底: 也按文本内容抓(万一走别的字符串源)
    log("就绪。搜索/点开被踢群触发提示。");
  });
})();
