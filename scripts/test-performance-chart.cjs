// Run with Node: node scripts/test-performance-chart.cjs [path/to/app.js]
// 用真实绘图源码 + 最小 DOM 夹具检验表现历史三图：位置横轴、一分钱、平价、负值、
// 极大值、缺价断线、资本流标记与可见性。浏览器实际渲染仍由 Root 另行核验。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function element(tag = 'div') {
  return {
    tag, attrs: {}, children: [], hidden: false, textContent: '', className: '', value: '',
    setAttribute(key, value) { this.attrs[key] = value; },
    removeAttribute(key) { delete this.attrs[key]; },
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.children = children; },
    querySelector(name) {
      this.queried = this.queried || {};
      if (!this.queried[name]) this.queried[name] = element(name);
      return this.queried[name];
    }
  };
}

const nodes = new Map();
const document = {
  getElementById(id) {
    if (!nodes.has(id)) {
      const tag = id.endsWith('-chart') ? 'svg' : (id.startsWith('performance-') ? 'div' : 'div');
      const node = element(tag);
      if (id.endsWith('-chart')) node.attrs.hidden = '';
      nodes.set(id, node);
    }
    return nodes.get(id);
  },
  createElement(tag) { return element(tag); },
  createElementNS(namespace, tag) { return element(tag); }
};

const source = fs.readFileSync(
  process.argv[2] || path.join(__dirname, '../src/main/resources/web/app.js'), 'utf8');

// 取真实源码片段：svgNode 与「表现历史」整块（含全部绘图函数），不复制实现。
const svgStart = source.indexOf('function svgNode(name, attributes, label) {');
const svgEnd = source.indexOf('\n}', svgStart) + 2;
assert.ok(svgStart > 0 && svgEnd > svgStart, 'svgNode 源码必须存在');
const blockStart = source.indexOf('const PERFORMANCE_CHART = {');
const blockEnd = source.indexOf('function renderPeopleAdmin(view) {');
assert.ok(blockStart > 0 && blockEnd > blockStart, '表现历史源码块必须存在');

const context = vm.createContext({
  document, BigInt, Math, Number, String, RegExp, Object, Array, Set, Map, JSON, console
});
context.state = { performanceRequest: 0, performanceHistory: null, mode: 'admin', page: 'performance' };
context.$ = (id) => document.getElementById(id);
context.fillTable = (id, rows, builder) => {
  const body = document.getElementById(id).querySelector('tbody');
  body.replaceChildren();
  for (const row of rows || []) {
    const tr = element('tr');
    builder(tr, row);
    body.appendChild(tr);
  }
  return body;
};
context.cell = (tr, value, className) => {
  const td = element('td');
  td.textContent = value === null || value === undefined || value === '' ? '—' : String(value);
  if (className) td.className = className;
  tr.appendChild(td);
  return td;
};
context.label = (group, value) => (value === undefined || value === null ? '—' : String(value));
context.text = (value) => (value === null || value === undefined || value === '' ? '—' : String(value));
context.setMessage = (id, message) => {
  const node = document.getElementById(id);
  node.textContent = message || '';
  node.hidden = !message;
};
context.sessionLost = () => { context.sessionLostCalled = true; };
context.guard = () => {};

vm.runInContext(source.slice(svgStart, svgEnd), context);
vm.runInContext(source.slice(blockStart, blockEnd), context);

function point(position, atMillis, nav, index, drawdown, flows) {
  return { position: String(position), timeMillis: String(atMillis), valuationStatus: 'COMPLETE',
    navBeforeFlow: nav, navAfterFlow: nav, index, drawdown, flows: flows || [] };
}

function draw(view) {
  context.view = view;
  vm.runInContext('renderPerformanceCharts(view)', context);
  return {
    scale: nodes.get('performance-scale-chart'),
    index: nodes.get('performance-index-chart'),
    drawdown: nodes.get('performance-drawdown-chart'),
    caption: nodes.get('performance-chart-caption')
  };
}

function circles(svg) {
  return svg.children.filter(node => node.tag === 'circle');
}

function paths(svg) {
  return svg.children.filter(node => node.tag === 'path');
}

function assertFinite(svg, label) {
  for (const node of svg.children) {
    for (const key of ['x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r']) {
      if (key in node.attrs) {
        assert.ok(Number.isFinite(Number(node.attrs[key])),
          `${label}.${node.tag}.${key} 必须是有限坐标，实际 ${node.attrs[key]}`);
      }
    }
    for (const child of node.children) {
      for (const key of ['x', 'y', 'cx', 'cy']) {
        if (key in child.attrs) assert.ok(Number.isFinite(Number(child.attrs[key])), `title 坐标 ${key}`);
      }
    }
  }
}

// 1) 同一毫秒、不同位置：横轴按位置分开，不重叠。
let charts = draw({ points: [
  point(3, 9007199254740993n.toString(), '1000.00', '1.000000000000', '0.000000000000'),
  point(4, 9007199254740993n.toString(), '1010.00', '1.010000000000', '0.000000000000'),
  point(5, 9007199254740993n.toString(), '1005.00', '1.005000000000', '0.004950495049')
], performance: { status: 'COMPLETE' } });
assert.ok(!('hidden' in charts.scale.attrs), '有数据时必须移除 hidden 才能显示');
const xs = circles(charts.scale).map(node => Number(node.attrs.cx));
assert.equal(xs.length, 3);
assert.equal(new Set(xs).size, 3, '同一毫秒的三个位置必须落在三个不同横坐标');
assert.ok(xs[0] < xs[1] && xs[1] < xs[2], '横坐标随确认位置递增');
assert.ok(charts.caption.textContent.includes('横轴 = 确认记录位置'), charts.caption.textContent);

// 2) 一分钱跨度（超大净值）：纵坐标必须分开，不能被压平。
charts = draw({ points: [
  point(1, 1000, '92233720368547758.08', '1.000000000000', '0.000000000000'),
  point(2, 2000, '92233720368547758.09', '1.000000000000', '0.000000000000')
], performance: { status: 'COMPLETE' } });
const pennyY = circles(charts.scale).map(node => Number(node.attrs.cy));
assert.ok(Math.abs(pennyY[0] - pennyY[1]) > 30,
  '一分钱跨度必须画出可见高度，实际差 ' + Math.abs(pennyY[0] - pennyY[1]));

// 3) 平价序列（全部相同）与单点：不得除零，坐标有限。
for (const nav of ['0.00', '0.01', '10.00', '92233720368547758.08', '-0.01']) {
  charts = draw({ points: [
    point(1, 1000, nav, '1.000000000000', '0.000000000000'),
    point(2, 2000, nav, '1.000000000000', '0.000000000000')
  ], performance: { status: 'COMPLETE' } });
  assertFinite(charts.scale, 'flat ' + nav);
}
charts = draw({ points: [point(7, 5000, '1234.56', '1.000000000000', '0.000000000000')],
  performance: { status: 'COMPLETE' } });
assertFinite(charts.scale, 'single');
assert.equal(circles(charts.scale).length, 1);

// 4) 负值：能画且不产生 Infinity。
charts = draw({ points: [
  point(1, 1000, '-500.00', '1.000000000000', '0.000000000000'),
  point(2, 2000, '-1200.50', '1.000000000000', '0.000000000000'),
  point(3, 3000, '-800.00', '1.000000000000', '0.000000000000')
], performance: { status: 'COMPLETE' } });
assertFinite(charts.scale, 'negative');
assert.ok(charts.scale.children.some(node => node.tag === 'text'
  && String(node.textContent).startsWith('-')), '纵轴需给出负值刻度');

// 5) 极大值（1e309 量级）：BigInt 精确缩放后仍是有限坐标。
const enormous = '1' + '0'.repeat(309);
charts = draw({ points: [
  point(1, 1000, enormous + '.00', '1.000000000000', '0.000000000000'),
  point(2, 2000, enormous + '.01', '1.000000000000', '0.000000000000')
], performance: { status: 'COMPLETE' } });
assertFinite(charts.scale, 'enormous');

// 6) 缺价点：资金规模断线（不插值）；期间不完整时服务端对每个点都给空指数，因此整幅留空。
const incompletePoints = [
  point(1, 1000, '1000.00', null, null),
  { position: '2', timeMillis: '2000', valuationStatus: 'MISSING_PRICE',
    navBeforeFlow: null, navAfterFlow: null, index: null, drawdown: null, flows: [] },
  point(3, 3000, '1200.00', null, null)
];
charts = draw({ points: incompletePoints, performance: { status: 'INCOMPLETE_VALUATION' } });
assert.ok(!('hidden' in charts.scale.attrs), '已知规模仍可画');
const moveCount = (paths(charts.scale)[0].attrs.d.match(/M/g) || []).length;
assert.equal(moveCount, 2, '缺价点必须断线：两段、不连线跨过缺口');
assert.ok('hidden' in charts.index.attrs, '指数不可用时整幅隐藏，不连假曲线');
assert.ok('hidden' in charts.drawdown.attrs, '回撤不可用时整幅隐藏');
assert.ok(charts.caption.textContent.includes('期间不完整'), charts.caption.textContent);

// 7) 资本流标记：位置上有竖线且标题写清方向与金额。
charts = draw({ points: [
  point(1, 1000, '1000.00', '1.000000000000', '0.000000000000'),
  point(2, 2000, '1500.00', '1.000000000000', '0.000000000000',
    [{ flowId: 'f1', kind: 'CAPITAL_CONTRIBUTION', amount: '500.00', position: '2' }]),
  point(3, 3000, '1450.00', '0.966666666667', '0.033333333333',
    [{ flowId: 'f2', kind: 'CAPITAL_WITHDRAWAL', amount: '50.00', position: '3' }])
], performance: { status: 'COMPLETE' } });
const markers = charts.scale.children.filter(node => String(node.attrs.class).includes('chart-flow'));
assert.equal(markers.length, 2);
assert.ok(markers[0].children[0].textContent.includes('流入 500.00'), markers[0].children[0].textContent);
assert.ok(markers[1].children[0].textContent.includes('流出 50.00'), markers[1].children[0].textContent);
assert.ok(charts.caption.textContent.includes('资金规模 元'));

// 8) 空区间：三幅图都隐藏，给空态文案。
charts = draw({ points: [], performance: { status: 'NO_PERIOD' } });
for (const svg of [charts.scale, charts.index, charts.drawdown]) {
  assert.ok('hidden' in svg.attrs, '空区间必须隐藏');
}
assert.equal(charts.caption.textContent, '所选区间没有确认点。');

// 8b) 12 位舍入边界：指数全为 0、回撤全为 1（合法正权益的极端下跌）也必须能画、坐标有限。
charts = draw({ points: [
  point(1, 1000, '1000000000000000000000000000000000000000000.00', '1.000000000000', '0.000000000000'),
  point(2, 2000, '0.01', '0.000000000000', '1.000000000000')
], performance: { status: 'COMPLETE' } });
assertFinite(charts.index, 'index-zero');
assertFinite(charts.drawdown, 'drawdown-one');
assert.ok(!('hidden' in charts.index.attrs), '指数显示为 0 仍是有效点，必须显示');
assert.ok(!('hidden' in charts.drawdown.attrs), '回撤显示为 1 仍是有效点，必须显示');
assert.ok(charts.caption.textContent.includes('期间不完整') === false, charts.caption.textContent);

// 9) 精度辅助函数：12 位指数与 2 位金额都不丢位。
const decimal = context.decimalToScaled;
assert.equal(decimal('92233720368547758.09', 2), 9223372036854775809n);
assert.equal(decimal('1.000000000001', 12), 1000000000001n);
assert.equal(decimal('-0.01', 2), -1n);
assert.equal(decimal('1.0', 12), 1000000000000n);
assert.equal(decimal('1e3', 2), null, '指数记数法不接受');
assert.equal(context.scaledToText(1000000000001n, 12), '1.000000000001');

console.log('PERFORMANCE_CHART_PASS position-axis, one-cent, flat, negative, 1e309, gap, flow markers, empty state, precision');
