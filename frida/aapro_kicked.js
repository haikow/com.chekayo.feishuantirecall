// 验证「别想玩消失」思路: hook 飞书 Chat 实体的成员/移除判定, 强行让被踢群"还在"。
// isRemoved/isDissolved/isDeleted -> false; isMember/isInChat -> true。看被踢群能否点开。
(function () {
  const OUT = "/data/data/com.ss.android.lark/aapro_kicked.log";
  let fh=null; try{fh=new File(OUT,"a");}catch(e){}
  function log(s){const l="[KICK] "+s;console.log(l);try{if(fh){fh.write(l+"\n");fh.flush();}}catch(e){}}
  log("==== kicked-view probe start ====");
  if(!Java||!Java.available){log("Java 不可用");return;}
  Java.perform(function(){
    var classes = [
      "com.ss.android.lark.chat.entity.chat.Chat",
      "com.bytedance.lark.pb.basic.v1.Chat"
    ];
    var forceFalse = ["isRemoved","isDissolved","isDeleted"];
    var forceTrue  = ["isMember","isInChat","isMeInChat"];
    classes.forEach(function(cn){
      var C; try{ C=Java.use(cn); }catch(e){ log("类不存在: "+cn); return; }
      function hookM(name, val){
        try{
          if(!C[name]) return;
          C[name].overloads.forEach(function(ov){
            try{ ov.implementation=function(){
              var r; try{ r=ov.apply(this,arguments); }catch(e){ r="?"; }
              var cnt=0;
              log(cn.split(".").pop()+"."+name+" 原="+r+" -> 改成 "+val);
              return val;
            }; }catch(e){}
          });
          log("已 hook "+cn+"."+name+" ("+C[name].overloads.length+"重载)");
        }catch(e){ log("hook "+name+" 失败: "+e); }
      }
      forceFalse.forEach(function(m){ hookM(m, false); });
      forceTrue.forEach(function(m){ hookM(m, true); });
    });
    log("就绪。现在去列表/搜索找那个被踢的群, 试着点开看能不能进。");
  });
})();
