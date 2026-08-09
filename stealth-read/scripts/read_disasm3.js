'use strict'
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
var probes = { 0x59dc03c: 'auto_open_v2', 0x59dc0c4: 'sdk_auto_open', 0x59dc1b0: 'put_read#2', 0x59dc394: 'put_read#3', 0x58771fc: 'gen_put_req_span' }

// 扫描 [from,to]，只打印 bl/b/blr/ret 和 prologue(stp x29/sub sp)，标注 probe 点
function scan(from, to) {
  var p = base.add(from)
  var end = base.add(to)
  console.log('\n===== scan 0x' + from.toString(16) + ' .. 0x' + to.toString(16) + ' =====')
  while (p.compare(end) < 0) {
    var off = p.sub(base).toInt32()
    var w = Memory.readU32(p)
    var ins
    try { ins = Instruction.parse(p) } catch (e) { p = p.add(4); continue }
    var mn = ins.mnemonic
    var interesting = /^(bl|b|blr|ret|br)$/.test(mn) || /stp x29, x30/.test(ins.opStr) || (mn === 'sub' && /sp, sp,/.test(ins.opStr))
    var mark = probes[off] ? '  <<<' + probes[off] : ''
    if (interesting) {
      var op = ins.opStr
      if (/^b/.test(mn) && op) { var m = /(0x[0-9a-f]+)/i.exec(op); if (m) { try { op = op + '  -> 0x' + ptr(m[1]).sub(base).toString(16) } catch (e) {} } }
      console.log('  0x' + off.toString(16) + '  ' + mn + ' ' + op + mark)
    }
    p = ins.next
  }
}
scan(0x59dbc00, 0x59dc3b0)
console.log('\n[done]')
