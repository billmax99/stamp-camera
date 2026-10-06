// 邮票相机 · 核心数据自检(直接从 index.html 抽取 CORE 块,防止两处分头实现走偏)
// 运行:node test.js
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const m = html.match(/\/\*CORE-START\*\/([\s\S]*?)\/\*CORE-END\*\//);
assert.ok(m, 'index.html 里找不到 CORE 标记块');
(0, eval)(m[1]);
assert.strictEqual(typeof perforationPath, 'function', 'CORE 块应定义 perforationPath');
for (const t of ['PERF_STYLES', 'CUT_MODES', 'MARK_STYLES', 'MARK_POS', 'MARK_INK', 'WEAR', 'SHAPES', 'WINDOWS',
  'PAPERS', 'BACKDROPS', 'SHUTTER_STYLES', 'BEAUTY', 'BLUR_BG', 'STICKERS', 'SIZE_PRESETS',
  'SHOOT_MODES', 'GRID_OPTS', 'PRODUCTS', 'STYLES', 'TEMPLATES']) {
  assert.ok(Array.isArray(eval(t)), `CORE 块应导出表 ${t}`);
}

// 齿孔 → 周长弧长坐标(顺时针,顶边起点)
function arc(h, W, H) {
  if (h.y === 0) return h.x;
  if (h.x === W) return W + h.y;
  if (h.y === H) return W + H + (W - h.x);
  return 2 * W + H + (H - h.y);
}

// 1) 真实邮票尺寸:数量正确、相邻(含首尾环绕)间距均匀、顺时针排布
{
  const W = 382, H = 510, s = 25;
  const holes = perforationPath(W, H, s);
  const P = 2 * (W + H), n = Math.max(8, Math.round(P / s));
  assert.strictEqual(holes.length, n, '齿孔数量');
  const arcs = holes.map(h => arc(h, W, H));
  for (let i = 0; i < n; i++) {
    const next = arcs[(i + 1) % n];
    const gap = i === n - 1 ? next + P - arcs[i] : next - arcs[i];
    assert.ok(Math.abs(gap - P / n) < 1e-9, `间距不均匀: ${gap} vs ${P / n}`);
  }
  console.log(`✓ 齿孔 ${n} 个,弧长间距 ${(P / n).toFixed(2)}px 均匀,顺时针排布`);
}

// 2) 每个齿孔都恰好落在矩形边框线上
{
  for (const h of perforationPath(382, 510, 25)) {
    assert.ok(h.x === 0 || h.x === 382 || h.y === 0 || h.y === 510, '离边点: ' + JSON.stringify(h));
  }
  console.log('✓ 所有齿孔都落在边框上');
}

// 3) 极小尺寸下限生效,起点在顶边
{
  const holes = perforationPath(40, 40, 25);
  assert.strictEqual(holes.length, 8, '下限 8 个');
  assert.strictEqual(holes[0].y, 0, '起点应在顶边');
  console.log('✓ 极小尺寸下限生效,起点在顶边');
}

// 4) 齿孔样式表:字段齐全、id 唯一、孔互不重叠
{
  const ids = new Set();
  for (const p of PERF_STYLES) {
    for (const k of ['id', 'name', 'shape', 'gap']) assert.ok(p[k] != null, `齿孔样式缺字段 ${k}: ${JSON.stringify(p)}`);
    assert.ok(!ids.has(p.id), '齿孔样式 id 重复: ' + p.id);
    ids.add(p.id);
    if (p.shape === 'none') continue;
    if (p.shape === 'circle') assert.ok(p.gap >= p.r * 2.2, `圆孔重叠: ${p.id}`);
    else assert.ok(p.w > 0 && p.gap >= p.w * 1.1, `异形孔重叠或缺宽: ${JSON.stringify(p)}`);
  }
  console.log(`✓ 齿孔样式 ${PERF_STYLES.length} 种(含光边),尺寸/齿距合法`);
}

// 5) 各表字段与取值合法
{
  for (const c of CUT_MODES) assert.ok(typeof c.dur === 'number' && c.dur >= 0 && typeof c.rev === 'boolean', `裁切方式非法: ${JSON.stringify(c)}`);
  assert.ok(MARK_STYLES.some(x => x.id === 'none'), '邮戳样式必须包含"不加盖"');
  assert.ok(MARK_POS.some(p => p.id === 'custom'), '邮戳位置必须包含"拖动摆放"(拖动交互的落点)');
  for (const p of MARK_POS) if (!['random', 'custom'].includes(p.id)) {
    for (const k of ['fx', 'fy', 'rot']) assert.ok(typeof p[k] === 'number', `邮戳位置缺 ${k}`);
    assert.ok(p.fx > 0 && p.fx < 1 && p.fy > 0 && p.fy < 1, `邮戳位置比例越界: ${JSON.stringify(p)}`);
  }
  for (const s of SHAPES) for (const k of ['win', 'cy', 'inset', 'dy', 'fs']) assert.ok(typeof s[k] === 'number', `外形缺数值字段 ${k}`);
  for (const p of PAPERS) assert.ok(/^#[0-9a-f]{6}$/i.test(p.fill), `纸面颜色非法: ${p.id}`);
  for (const b of BACKDROPS) assert.ok(typeof b.css === 'string' && b.css.length > 5, `背景缺 css: ${b.id}`);
  for (const s of SHUTTER_STYLES) assert.ok('cls' in s && 'inner' in s, `快门样式缺字段: ${s.id}`);
  for (const b of BEAUTY.concat(BLUR_BG)) assert.ok(b.name && b.id != null, `美颜/虚化缺字段: ${JSON.stringify(b)}`);
  for (const s of STICKERS) for (const k of ['e', 'name', 'x', 'y', 's']) assert.ok(s[k] != null, `贴饰缺字段 ${k}`);
  for (const s of STICKERS) if (s.fx !== undefined) {
    // 跟脸贴饰:锚点相对脸框,fs 相对脸宽;fx/fy 允许为负或>1(耳侧/头顶/框外)
    for (const k of ['fy', 'fs']) assert.ok(typeof s[k] === 'number', `跟脸贴饰 ${s.id} 缺 ${k}`);
    assert.ok(s.fx >= -0.5 && s.fx <= 1.5, `跟脸贴饰 ${s.id} fx 越界`);
    assert.ok(s.fs > 0 && s.fs <= 1.5, `跟脸贴饰 ${s.id} fs 越界`);
  }
  for (const s of SIZE_PRESETS) assert.ok(s.k > 0.5 && s.k < 1.5, `邮票大小越界: ${JSON.stringify(s)}`);
  assert.ok(SHOOT_MODES.some(s => s.id === 'single') && SHOOT_MODES.some(s => s.id === 'sheet4'), '拍摄模式需含单张与四连张');
  assert.ok(PRODUCTS.some(p => p.id === 'stamp'), '纸品需含单枚邮票');
  assert.strictEqual(new Set(PRODUCTS.map(p => p.id)).size, PRODUCTS.length, '纸品 id 需唯一');
  for (const arr of [MARK_INK, WEAR]) {
    assert.ok(arr.every(x => x.id && x.name), `表缺 id/name: ${JSON.stringify(arr)}`);
    assert.strictEqual(new Set(arr.map(x => x.id)).size, arr.length, '表 id 重复');
  }
  // 齿孔不可及深度:底部文字(基线=464+31,小字8px)必须低于最大齿孔侵入线 510-14=496
  {
    const maxR = Math.max(...PERF_STYLES.filter(p => p.shape === 'circle').map(p => p.r));
    const intrusion = 510 - maxR;           // 底边齿孔能摸到的最高 y
    const subBaseline = 464 + 31;           // 小字基线
    assert.ok(subBaseline <= intrusion + 1, `小字基线 ${subBaseline} 会被齿孔(${intrusion})割到`);
    const mainBaseline = 464 + 20, mainFont = 21, desc = 4;
    assert.ok(mainBaseline + desc <= intrusion + 1, '主文字会被齿孔割到');
    console.log(`✓ 文字带位于齿孔安全区(侵入线 y=${intrusion},主字底≈${mainBaseline + desc},小字底=${subBaseline})`);
  }
  console.log(`✓ 裁切${CUT_MODES.length}种/邮戳位${MARK_POS.length}种/外形${SHAPES.length}×内窗${WINDOWS.length}/纸面${PAPERS.length}/背景${BACKDROPS.length}/快门${SHUTTER_STYLES.length}/贴饰${STICKERS.length} 字段合法`);
}

// 6) 模板与整体风格:所有 id 引用可解析,文案非空,默认模板存在
{
  const ref = {
    paper: PAPERS, backdrop: BACKDROPS, shape: SHAPES, window: WINDOWS,
    perf: PERF_STYLES, cut: CUT_MODES, mark: MARK_STYLES, shutter: SHUTTER_STYLES
  };
  const check = (obj, label) => {
    assert.ok(obj.id && obj.name, label + ' 缺 id/name');
    for (const k of Object.keys(ref)) assert.ok(ref[k].some(x => x.id === obj[k]), `${label} ${obj.id} 引用了不存在的 ${k}:${obj[k]}`);
  };
  for (const s of STYLES) check(s, '整体风格');
  for (const t of TEMPLATES) {
    check(t, '模板');
    assert.ok(t.issuer && t.value, '模板文案不能为空: ' + t.id);
    assert.ok(/^#[0-9a-f]{6}$/i.test(t.valueColor), `模板 ${t.id} 面值颜色非法`);
  }
  assert.ok(TEMPLATES.some(t => t.id === DEFAULT_TEMPLATE), '默认模板必须存在');
  console.log(`✓ 模板 ${TEMPLATES.length} 套 + 整体风格 ${STYLES.length} 套,引用全部可解析,默认模板 ${DEFAULT_TEMPLATE} 有效`);
}

console.log('OK: 6/6 组断言通过');
