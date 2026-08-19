'use strict'
var lark = Process.findModuleByName('liblark.so')
var base = lark.base
var marks = { 0x59dc03c: 'auto_open_v2 str', 0x59dc0c4: 'sdk_auto_open str', 0x59dc1b0: 'put_read#2 str', 0x59dc394: 'put_read#3 str' }

function dumpFull(from, to) {
  console.log('\n===== full 0x' + from.toString(16) + ' .. 0x' + to.toString(16) + ' =====')
  var p = base.add(from), end = base.add(to)
  while (p.compare(end) < 0) {
    var off = p.sub(base).toInt32()
    var ins
    try { ins = Instruction.parse(p) } catch (e) { console.log('  0x' + off.toString(16) + '  <udf>'); p = p.add(4); continue }
    var op = ins.opStr
    if (/^b/.test(ins.mnemonic) && op) { var m = /(0x[0-9a-f]+)/i.exec(op); if (m) { try { op = op + '  -> 0x' + ptr(m[1]).sub(base).toString(16) } catch (e) {} } }
    var mk = marks[off] ? '  <<<' + marks[off] : ''
    console.log('  0x' + off.toString(16) + '  ' + ins.mnemonic + ' ' + op + mk)
    p = ins.next
  }
}
// auto_open_v2 字符串加载点前后，看紧随的"提交"调用
dumpFull(0x59dc020, 0x59dc0e0)
console.log('\n[done]')
