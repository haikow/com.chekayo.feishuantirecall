'use strict'
// v3: 修好解码器（& 后 >>>0），扫描真正 .text，找引用 put_read 命令串的 adrp+add
var lark = Process.findModuleByName('liblark.so')
var base = lark.base

var STR_OFF = 0x11b6991 // "im.message.put_read" 起点
var strAbs = base.add(STR_OFF)
var strPageOff = STR_OFF & 0xFFFFF000 // 0x11b6000
var pageAbs = base.add(strPageOff)
var PAGE_MASK = ptr('0xFFFFFFFFFFFFF000')

try { console.log('[xref] strAt ' + strAbs + ' -> "' + Memory.readUtf8String(strAbs, 30) + '"') } catch (e) {}

function decodeAdrp(instr) {
  var m = ((instr >>> 0) & 0x9F000000) >>> 0
  if (m !== 0x90000000) return null
  var immlo = (instr >>> 29) & 0x3
  var immhi = (instr >>> 5) & 0x7FFFF
  var imm21 = (immhi << 2) | immlo
  if (imm21 & 0x100000) imm21 -= 0x200000
  return { rd: instr & 0x1F, imm: imm21 << 12 }
}
function decodeAddImm(instr) {
  var m = ((instr >>> 0) & 0xFF800000) >>> 0
  if (m !== 0x91000000) return null
  return { rd: instr & 0x1F, rn: (instr >>> 5) & 0x1F, imm12: (instr >>> 10) & 0xFFF, sh: (instr >>> 22) & 0x3 }
}

var refs = []
var totalAdrp = 0
var CHUNK = 0x10000
var ranges = lark.enumerateRanges('r-x')
ranges.forEach(function (r) {
  var off = 0
  while (off + 8 <= r.size) {
    var step = Math.min(CHUNK, r.size - off)
    var buf
    try { buf = Memory.readByteArray(r.base.add(off), step) } catch (e) { off += step; continue }
    var u32 = new Uint32Array(buf)
    for (var i = 0; i + 1 < u32.length; i++) {
      var a = decodeAdrp(u32[i])
      if (!a) continue
      totalAdrp++
      var pc = r.base.add(off + i * 4)
      var tpage = pc.and(PAGE_MASK).add(a.imm)
      if (!tpage.equals(pageAbs)) continue
      var add = decodeAddImm(u32[i + 1])
      if (!add || add.rn !== a.rd) continue
      var dataAddr = tpage.add(add.sh ? (add.imm12 << 12) : add.imm12)
      var dataOff = dataAddr.sub(base).toInt32()
      if (dataOff < strPageOff || dataOff > strPageOff + 0x1000) continue
      var preview = ''
      try { preview = Memory.readUtf8String(dataAddr, 50) } catch (e) {}
      refs.push({ code: '0x' + pc.sub(base).toString(16), data: '0x' + dataOff.toString(16), reg: a.rd, pv: (preview || '').replace(/\n/g, ' ') })
    }
    off += step
  }
})

console.log('[xref] totalAdrp=' + totalAdrp + '  refs-to-put_read-page=' + refs.length)
// 优先显示精确命中命令串起点的
var exact = refs.filter(function (x) { return x.data === '0x11b6991' })
console.log('[xref] exact refs to 0x11b6991 (cmd start): ' + exact.length)
exact.forEach(function (x) { console.log('  ★ code=' + x.code + ' data=' + x.data + ' r' + x.reg + '  "' + x.pv + '"') })
console.log('[xref] -- other refs on the page --')
refs.filter(function (x) { return x.data !== '0x11b6991' }).slice(0, 20).forEach(function (x) { console.log('   code=' + x.code + ' data=' + x.data + ' r' + x.reg + '  "' + x.pv + '"') })
console.log('[xref] done')
