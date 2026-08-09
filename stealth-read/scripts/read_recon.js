'use strict';
// 只读侦察：定位 put_read 字符串 + 摸网络写原语归属
console.log('[recon] start');

// 1) liblark.so 里找 "put_read" 字符串
var lark = Process.findModuleByName('liblark.so');
if (!lark) {
  console.log('[recon] liblark.so NOT FOUND');
} else {
  console.log('[recon] liblark.so base=' + lark.base + ' size=0x' + lark.size.toString(16) + ' path=' + lark.path);
  var pat = '70 75 74 5F 72 65 61 64'; // "put_read"
  var hits = [];
  try { hits = Memory.scanSync(lark.base, lark.size, pat); } catch (e) { console.log('[recon] scan err: ' + e); }
  console.log('[recon] put_read hits in liblark.so: ' + hits.length);
  hits.slice(0, 8).forEach(function (m) {
    var off = m.address.sub(lark.base);
    var ctx = '';
    try { ctx = Memory.readUtf8String(m.address.sub(8), 48); } catch (e) {}
    console.log('  @ ' + m.address + ' off=0x' + off.toString(16) + '  ctx="' + (ctx||'').replace(/\n/g,' ') + '"');
  });
}

// 2) 网络相关模块
console.log('\n[recon] network-ish modules:');
Process.enumerateModules().forEach(function (mod) {
  if (/ssl|crypto|boringssl|quic|cronet|ttnet|net|lark/i.test(mod.name)) {
    console.log('  ' + mod.name + '  base=' + mod.base + ' size=0x' + mod.size.toString(16));
  }
});

// 3) 在这些模块里找写原语导出
console.log('\n[recon] write-primitive exports:');
var want = /^SSL_write$|^send$|^sendto$|^sendmsg$|^writev?$|^SSL_send$|^BoringSSL_send/;
Process.enumerateModules().forEach(function (mod) {
  if (!/ssl|crypto|boringssl|quic|cronet|ttnet|liblark|libc\.so/i.test(mod.name)) return;
  try {
    mod.enumerateExports().forEach(function (e) {
      if (want.test(e.name)) console.log('  ' + mod.name + ' ! ' + e.name + ' @ ' + e.address);
    });
  } catch (e) {}
});

console.log('[recon] done');
