'use strict'
var lark = Process.findModuleByName('liblark.so')
var base = lark.base

function dump(offLabel, off, before, after) {
  var addr = base.add(off)
  console.log('\n===== ' + offLabel + ' off=0x' + off.toString(16) + ' =====')
  var startOff = off - before * 4; if (startOff < 0) startOff = 0
  var p = base.add(startOff)
  var end = addr.add(after * 4)
  var n = 0
  while (n < before + after + 4 && p.compare(end) < 0) {
    var w = Memory.readU32(p)
    var ins
    try { ins = Instruction.parse(p) } catch (e) { console.log('  0x' + p.sub(base).toString(16) + '  <udf>'); p = p.add(4); n++; continue }
    var mark = p.equals(addr) ? ' <<<' : ''
    var op = ins.mnemonic + ' ' + ins.opStr
    if (/^b/.test(ins.mnemonic) && ins.opStr) {
      var m = /(0x[0-9a-f]+)/i.exec(ins.opStr)
      if (m) { try { op = op + '   -> 0x' + ptr(m[1]).sub(base).toString(16) } catch (e) {} }
    }
    console.log('  0x' + p.sub(base).toString(16) + '  ' + op + mark)
    p = ins.next; n++
  }
}

// gen_put_req 本体：看它返回什么、是否纯构造请求
dump('gen_put_req body', 0x58771d0, 0, 70)
// 调用者：bl gen_put_req 在 ~0x5aee600，看它前后（尤其之后的 send）
dump('caller of gen_put_req', 0x5aee604, 26, 34)
console.log('\n[done]')
