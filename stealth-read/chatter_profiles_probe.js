// chatter_profiles_probe.js —— [v6 确认 tenant_id 是否按公司区分 + blob 里的公司标记]
// 目的: 敲定"同不同公司"的判据。查 chatters 按 tenant_id 分组计数 + 各组抽样(名字/邮箱域),
//       并在 v3 blob 里找 tenant_name/公司字段, 看外部群客户的人能否标出其公司。
//
// 机制/坑同前: hook sqlite3_prepare_v2 + busy 重入锁。
// 用法: 算法助手加载 -> 进飞书【通讯录】滑一圈, 最好也点开一个外部群看下成员触发外部人加载。
//       落文件: /data/data/com.ss.android.lark/files/resign_tracker/tenant_probe_<tag>.json

(function () {
  var OUT_DIR = '/data/data/com.ss.android.lark/files/resign_tracker';
  var PS = Process.pointerSize;
  function log(s) { try { console.log('[cpp] ' + s); } catch (x) {} }
  function whenSql(cb) {
    var m = Process.findModuleByName('libsqlcipher.so');
    if (m) { cb(m); return; }
    var t = setInterval(function () { var mm = Process.findModuleByName('libsqlcipher.so'); if (mm) { clearInterval(t); cb(mm); } }, 100);
  }
  function toHex(ab){var u=new Uint8Array(ab),s='';for(var i=0;i<u.length;i++){var h=u[i].toString(16);if(h.length<2)h='0'+h;s+=h;}return s;}

  whenSql(function (m) {
    var e = {};
    ['sqlite3_prepare_v2','sqlite3_step','sqlite3_finalize','sqlite3_column_count','sqlite3_column_name',
     'sqlite3_column_text','sqlite3_column_blob','sqlite3_column_bytes','sqlite3_db_filename'].forEach(function(n){
      try{e[n]=m.getExportByName(n);}catch(x){log('缺符号 '+n);}
    });
    var prep=new NativeFunction(e.sqlite3_prepare_v2,'int',['pointer','pointer','int','pointer','pointer']);
    var step=new NativeFunction(e.sqlite3_step,'int',['pointer']);
    var fin=new NativeFunction(e.sqlite3_finalize,'int',['pointer']);
    var ccount=new NativeFunction(e.sqlite3_column_count,'int',['pointer']);
    var cname=new NativeFunction(e.sqlite3_column_name,'pointer',['pointer','int']);
    var ctext=new NativeFunction(e.sqlite3_column_text,'pointer',['pointer','int']);
    var cblob=new NativeFunction(e.sqlite3_column_blob,'pointer',['pointer','int']);
    var cbytes=new NativeFunction(e.sqlite3_column_bytes,'int',['pointer','int']);
    var fname=new NativeFunction(e.sqlite3_db_filename,'pointer',['pointer','pointer']);

    function query(db,sql){
      var pp=Memory.alloc(PS),cs=Memory.allocUtf8String(sql);
      if(prep(db,cs,-1,pp,ptr(0))!==0)return null;
      var st=pp.readPointer();if(st.isNull())return null;
      var n=ccount(st),cols=[];for(var i=0;i<n;i++){var np=cname(st,i);cols.push(np.isNull()?('c'+i):np.readUtf8String());}
      var rows=[];
      while(step(st)===100){var o={};for(var j=0;j<n;j++){var tp=ctext(st,j);o[cols[j]]=tp.isNull()?null:(function(){try{return tp.readUtf8String();}catch(x){return null;}})();}rows.push(o);}
      fin(st);return{cols:cols,rows:rows};
    }
    function scalar(db,sql){var r=query(db,sql);return(r&&r.rows.length)?r.rows[0][r.cols[0]]:null;}
    function writeFile(p,s){try{var f=new File(p,'w');f.write(s);f.close();return true;}catch(x){log('写文件失败 '+x);return false;}}

    var busy=false,seen={};
    Interceptor.attach(e.sqlite3_prepare_v2,{onEnter:function(a){
      if(busy)return;var db=a[0];if(db.isNull())return;var key=db.toString();if(seen[key])return;
      busy=true;
      try{
        if(scalar(db,'SELECT count(*) FROM chatters')===null)return;
        seen[key]=1;
        var fn='';try{var fp=fname(db,Memory.allocUtf8String('main'));if(!fp.isNull())fn=fp.readUtf8String();}catch(x){}

        // 1) chatters 按 tenant_id 分组计数
        var g=query(db,"SELECT tenant_id, count(*) AS n FROM chatters GROUP BY tenant_id ORDER BY n DESC");
        // 2) 每个 tenant 抽 3 个样本(名字), 看是不是同公司
        var samplesByTenant={};
        if(g){g.rows.forEach(function(r){
          var tidv=r.tenant_id;var q="SELECT id,name FROM chatters WHERE tenant_id"+(tidv===null?" IS NULL":"='"+tidv+"'")+" LIMIT 3";
          var s=query(db,q);samplesByTenant[tidv===null?'NULL':tidv]=(s?s.rows.map(function(x){return x.name;}):[]);
        });}
        // 3) 抓一个"没有 @公司域名 邮箱/外部"的人的 v3 blob, 看里面有没有公司名字段
        //    先找一个 tenant 与主体不同的人
        var mainTid=g&&g.rows.length?g.rows[0].tenant_id:null;
        var extBlobHex='',extName='',extTid='';
        if(g){
          for(var i=0;i<g.rows.length;i++){
            var r=g.rows[i];if(r.tenant_id===mainTid)continue;
            var one=query(db,"SELECT v.chatter_id,c.name FROM chatter_profiles_v3 v JOIN chatters c ON c.id=v.chatter_id WHERE c.tenant_id"+(r.tenant_id===null?" IS NULL":"='"+r.tenant_id+"'")+" AND length(v.profile)>0 LIMIT 1");
            if(one&&one.rows.length){
              extName=one.rows[0].name;extTid=r.tenant_id===null?'NULL':r.tenant_id;
              // 读该人 blob
              var pp=Memory.alloc(PS),cs=Memory.allocUtf8String("SELECT profile FROM chatter_profiles_v3 WHERE chatter_id="+one.rows[0].chatter_id);
              if(prep(db,cs,-1,pp,ptr(0))===0){var st=pp.readPointer();if(step(st)===100){var bp=cblob(st,0),bn=cbytes(st,0);if(!bp.isNull()&&bn>0){try{extBlobHex=toHex(bp.readByteArray(bn));}catch(x){}}}fin(st);}
              break;
            }
          }
        }

        var out={file:fn,main_tenant_id:mainTid,tenant_groups:g?g.rows:null,samples_by_tenant:samplesByTenant,
                 ext_sample_name:extName,ext_sample_tenant:extTid,ext_sample_blob_hex:extBlobHex};
        var tag=(mainTid||key.slice(-8));
        var path=OUT_DIR+'/tenant_probe_'+tag+'.json';
        writeFile(path,JSON.stringify(out,null,1));
        log('主tenant='+mainTid+' 分组='+(g?g.rows.length:0)+' 外部样本='+extName+'(tenant='+extTid+') -> '+path);
        if(g)g.rows.slice(0,8).forEach(function(r){log('  tenant='+r.tenant_id+' 人数='+r.n+' 例:'+(samplesByTenant[r.tenant_id===null?'NULL':r.tenant_id]||[]).join(','));});
      }catch(err){log('err '+err);}
      finally{busy=false;}
    }});
    log('armed(v6, tenant判据) —— 进飞书【通讯录】滑一圈, 有外部群也点开看下成员');
  });
})();
