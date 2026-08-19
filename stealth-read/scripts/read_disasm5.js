'use strict'
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
var marks = { 0x59dc1b0: 'put_read#2 span', 0x59dc394: 'put_read#3 span', 0x59dc03c: 'auto_open', 0x59dc0c4: 'sdk_auto_open' }
function dump(from, to) {
  console.log('\n===== bl/struct 0x' + from.toString(16) + '..0x' + to.toString(16) + ' =====')
  var p = base.add(from), end = base.add(to)
  while (p.compare(end) < 0) {
    var off = p.sub(base).toInt32()
    var ins
    try { ins = Instruction.parse(p) } catch (e) { p = p.add(4); continue }
    var mn = ins.mnemonic, op = ins.opStr
    var interesting = /^(bl|bne|beq|cbz|cbnz|tbz|tbnz|b)$/.test(mn) || /stp x29, x30/.test(op) || (mn === 'sub' && /sp, sp,/.test(op)) || (mn === 'adrp')
    if (interesting) {
      if (/^b/.test(mn) && op) { var m = /(0x[0-9a-f]+)/i.exec(op); if (m) { try { op = op + '  -> 0x' + ptr(m[1]).sub(base).toString(16) } catch (e) {} } }
      if (mn === 'adrp') { try { var imm = ins.operands[1].value; op = op + '  datapage=0x' + ptr(imm).sub(base).toString(16) } catch (e) {} }
      var mk = marks[off] ? '  <<<' + marks[off] : ''
      console.log('  0x' + off.toString(16) + '  ' + mn + ' ' + op + mk)
    }
    p = ins.next
  }
}
dump(0x59dc0e0, 0x59dc3a0)
console.log('\n[done]')
