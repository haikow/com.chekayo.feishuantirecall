// 抓"你已不在该群聊"弹出时的调用栈 -> 定位门控。hook Toast + 常见 lark toast。
(function () {
  const OUT="/data/data/com.ss.android.lark/aapro_gate.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[GATE] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== gate probe start ====");
  if(!Java||!Java.available){log("Java 不可用");return;}
  Java.perform(function(){
    var Ex=Java.use("java.lang.Exception"), Log=Java.use("android.util.Log");
    function stk(){ try{return Log.getStackTraceString(Ex.$new());}catch(e){return "?";} }
    function hit(where, txt){
      var t=""+txt;
      if(t.indexOf("不在该群")>=0||t.indexOf("移出群")>=0||t.indexOf("群聊不存在")>=0||t.indexOf("不在群")>=0){
        log("★ 命中["+where+"] text="+t+"\n"+stk());
      }
    }
    // 1) 系统 Toast
    try{ var T=Java.use("android.widget.Toast");
      T.makeText.overloads.forEach(function(ov){ try{ ov.implementation=function(){ try{hit("Toast.makeText",arguments[1]);}catch(e){} return ov.apply(this,arguments); }; }catch(e){} });
      log("hook Toast.makeText"); }catch(e){log("Toast hook 失败:"+e);}
    // 2) 常见 lark toast 工具(按类名尝试)
    var cands=["com.ss.android.lark.toast.LKUIToast","com.larksuite.framework.utils.ToastUtils","com.ss.android.lark.m"+"onitor.Toast","com.ss.android.lark.ui.LKUIToast"];
    cands.forEach(function(cn){
      try{ var C=Java.use(cn);
        ["show","showToast","showFailure","showCommonFail","make"].forEach(function(mn){
          try{ if(C[mn]) C[mn].overloads.forEach(function(ov){ try{ ov.implementation=function(){ for(var i=0;i<arguments.length;i++){ try{hit(cn+"."+mn,arguments[i]);}catch(e){} } return ov.apply(this,arguments); }; }catch(e){} }); }catch(e){}
        });
        log("hook "+cn);
      }catch(e){}
    });
    log("就绪。现在搜索/点开被踢群, 触发'你已不在该群聊'提示。");
  });
})();
