'use strict'
// 反汇编 put_read 管线关键代码点周围，找函数边界 + send 调用
var lark = Process.findModuleByName('liblark.so')
var base = lark.base

function isRet(w) { return (w >>> 0) === 0xD65F03C0 }
function dumpAround(offLabel, off, before, after) {
  var addr = base.add(off)
  console.log('\n===== ' + offLabel + ' (off=0x' + off.toString(16) + ', abs=' + addr + ') =====')
  // 从 off-before*4 处向前反汇编
  var startOff = off - before * 4
  if (startOff < 0) startOff = 0
  var p = base.add(startOff)
  var count = 0
  var targetSeen = false
  while (count < before + after && p.compare(addr.add(after * 4)) < 0) {
    var w = Memory.readU32(p)
    var ins
    try { ins = Instruction.parse(p) } catch (e) { console.log('  ' + p.sub(base).toString() + '  <udf 0x' + (w >>> 0).toString(16) + '>'); p = p.add(4); count++; continue }
    var mark = (p.equals(addr)) ? ' <<<' : ''
    var op = ins.mnemonic + ' ' + ins.opStr
    if (ins.mnemonic === 'bl' || ins.mnemonic === 'b') {
      // 解析目标地址
      var m = /#?(0x[0-9a-f]+)/i.exec(ins.opStr)
      if (m) {
        var tgt = ptr(m[1])
        op = op + '   -> off=0x' + tgt.sub(base).toString(16)
      }
    }
    console.log('  ' + p.sub(base).toString() + '  ' + op + mark)
    p = ins.next
    count++
  }
}

// 找每个目标所在函数：先反汇编它前面一段，能看到 prologue 和 ret 边界
dumpAround('cmd-string-log @0x5b36fc8', 0x5b36fc8, 50, 8)
dumpAround('gen_put_req @0x58771fc', 0x58771fc, 40, 12)
console.log('\n[xref] done')
