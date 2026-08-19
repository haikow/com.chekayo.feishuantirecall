'use strict';
// 按可读段扫描 liblark.so，定位 put_read 字符串及上下文
var lark = Process.findModuleByName('liblark.so');
console.log('[scan] liblark.so base=' + lark.base + ' size=0x' + lark.size.toString(16));

function scanFor(label, pattern) {
  var total = 0;
  var samples = [];
  lark.enumerateRanges('r--').forEach(function (r) {
    try {
      Memory.scanSync(r.base, r.size, pattern).forEach(function (m) {
        total++;
        if (samples.length < 6) {
          var off = m.address.sub(lark.base);
          var before = '', after = '';
          try { before = Memory.readUtf8String(m.address.sub(24), 24); } catch (e) {}
          try { after = Memory.readUtf8String(m.address, 48); } catch (e) {}
          samples.push({ off: '0x' + off.toString(16), before: before, after: after });
        }
      });
    } catch (e) {}
  });
  console.log('[scan] ' + label + ' hits=' + total);
  samples.forEach(function (s) {
    console.log('   off=' + s.off + '  before="' + (s.before || '').replace(/\n/g, ' ') + '"  after="' + (s.after || '').replace(/\n/g, ' ') + '"');
  });
}

scanFor('put_read', '70 75 74 5F 72 65 61 64');
scanFor('im.message.put_read', '69 6D 2E 6D 65 73 73 61 67 65 2E 70 75 74 5F 72 65 61 64');
console.log('[scan] done');
