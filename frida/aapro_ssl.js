// aapro_ssl.js —— 在 TLS 边界抓明文,判断组织 RPC 是否可读(C 方案可行性实验)
// hook libttboringssl.so 的 SSL_write / SSL_read,抽取 buffer 里的可读 ASCII 串,
// 只记录疑似"通讯录/组织/成员"相关的流量,写 /data/data/com.ss.android.lark/files/aapro_ssl.log
(function () {
  var OUT = '/data/data/com.ss.android.lark/files/aapro_ssl.log';
  var lf = null; try { lf = new File(OUT, 'a'); } catch (e) {}
  function log(s){ try{ if(lf){ lf.write(s+'\n'); lf.flush(); } }catch(e){} console.log(s); }
  log('==== ssl probe start ' + new Date().toString() + ' ====');

  var KW = /contact|chatter|department|dept|organization|scope|member|GetChatter|MGetChatter|address_book|user_id|tenant|员工|部门|通讯录/i;
  var PATH = /(\/[a-z0-9_\-\/]{6,})|([a-z_]+\.[a-z_]+\.[a-zA-Z]+Service)|(Get[A-Z][A-Za-z]+)/;

  function asciiRuns(buf, n){
    var runs=[], cur='';
    for (var i=0;i<n;i++){ var c=buf[i];
      if (c>=0x20 && c<0x7e){ cur+=String.fromCharCode(c); }
      else { if(cur.length>=6) runs.push(cur); cur=''; }
    }
    if(cur.length>=6) runs.push(cur);
    return runs;
  }

  function whenMod(name, cb){
    var m=Process.findModuleByName(name); if(m){cb(m);return;}
    var t=setInterval(function(){ var mm=Process.findModuleByName(name); if(mm){clearInterval(t); cb(mm);} },150);
  }

  whenMod('libttboringssl.so', function(m){
    var wr=null, rd=null;
    try{ wr=m.getExportByName('SSL_write'); }catch(e){}
    try{ rd=m.getExportByName('SSL_read'); }catch(e){}
    log('libttboringssl base='+m.base+' SSL_write='+wr+' SSL_read='+rd);
    if(!wr){ log('!! 没导出 SSL_write, 尝试 SSL_write_ex'); try{ wr=m.getExportByName('SSL_write_ex'); }catch(e){} }

    var cW=0, cR=0, cap=0, CAPMAX=400, raw=0, RAWMAX=30;
    function handle(tag, buf, n){
      if (tag==='W') cW++; else cR++;
      if (n<=0) return;
      var runs;
      try{ runs = asciiRuns(buf, Math.min(n, 4096)); }catch(e){ return; }
      // 先无脑 dump 前 RAWMAX 次(判断 SSL 到底走不走这条路)
      if (raw<RAWMAX){ raw++; log('[RAW '+tag+' n='+n+'] '+runs.slice(0,10).join(' | ').slice(0,300)); }
      var joined = runs.join(' | ');
      if (cap<CAPMAX && (KW.test(joined)||PATH.test(joined))){
        cap++;
        log('['+tag+' n='+n+'] '+runs.filter(function(r){return KW.test(r)||PATH.test(r);}).slice(0,12).join('  ‖  '));
      }
    }
    setInterval(function(){ log('[counter] SSL_write='+cW+' SSL_read='+cR); }, 4000);

    if(wr) Interceptor.attach(wr, { onEnter:function(a){ try{ handle('W', a[1], a[2].toInt32()); }catch(e){} } });
    if(rd) Interceptor.attach(rd, { onLeave:function(ret){ try{ var n=ret.toInt32(); if(n>0) handle('R', this._b, n); }catch(e){} },
                                    onEnter:function(a){ this._b=a[1]; } });
    log('armed —— 现在开一个部门,抓通讯录相关明文');
  });
})();
