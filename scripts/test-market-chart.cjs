// Run with Node: node scripts/test-market-chart.cjs [path/to/app.js]
// Exercise the real drawing function with a minimal DOM; browser layout is verified separately.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function element(tag = 'div') {
  return { tag, attrs: {}, children: [], hidden: false, textContent: '',
    setAttribute(key, value) { this.attrs[key] = value; },
    removeAttribute(key) { delete this.attrs[key]; },
    appendChild(child) { this.children.push(child); },
    replaceChildren(...children) { this.children = children; } };
}
const nodes = new Map();
const document = {
  getElementById(id) {
    if (!nodes.has(id)) {
      const node = element(id === 'candle-chart' ? 'svg' : 'div');
      if (id === 'candle-chart') node.attrs.hidden = '';
      nodes.set(id, node);
    }
    return nodes.get(id);
  },
  createElementNS(namespace, tag) { return element(tag); }
};
const context = vm.createContext({ document });
const source = fs.readFileSync(process.argv[2] || path.join(__dirname, '../src/main/resources/web/app.js'), 'utf8');
vm.runInContext(source.split('// ---------------------------------------------------------------- 绑定')[0], context);
function draw(open, close, volume = '9223372036854775807', complete = true) {
  const increasing = BigInt(open.replace('.', '')) <= BigInt(close.replace('.', ''));
  context.view = { selectedSymbol: 'A', period: 'minute', totalBars: '1', bars: [{
    startMillis: '9007199254740993', endMillis: '9007199254800000',
    firstTradeId: '1', lastTradeId: '2', count: '2', open, close,
    low: increasing ? open : close, high: increasing ? close : open,
    volume, amount: '184467440737095516.17', complete
  }] };
  vm.runInContext('renderCandleChart(view)', context);
  const svg = nodes.get('candle-chart');
  assert.equal('hidden' in svg.attrs, false, 'SVG must remove the hidden attribute to become visible');
  for (const node of svg.children) for (const key of ['x', 'y', 'x1', 'y1', 'x2', 'y2', 'width', 'height']) {
    if (key in node.attrs) assert.ok(Number.isFinite(Number(node.attrs[key])), `${key} must be finite`);
  }
  return svg.children.find(node => node.tag === 'rect' && node.attrs.class.includes('chart-body'));
}

let body = draw('92233720368547758.08', '92233720368547758.09');
assert.ok(Number(body.attrs.height) > 50, 'one cent above Number precision must remain visible');
assert.ok(body.attrs.class.includes('chart-up'));
assert.ok(body.children[0].textContent.includes('92233720368547758.09'));
body = draw('92233720368547758.09', '92233720368547758.08');
assert.ok(body.attrs.class.includes('chart-down'), 'large down tick must not become flat/up');
for (const flat of ['0.01', '0.05', '1.00', '3.99', '4.00', '10.00', '92233720368547758.08']) {
  assert.equal(Number(draw(flat, flat).attrs.height), 1, `flat ${flat} must render without dividing by zero`);
}
const enormous = '1' + '0'.repeat(309);
body = draw(enormous + '.00', enormous + '.01', null, false);
assert.ok(Number(body.attrs.height) > 50);
assert.ok(body.attrs.class.includes('chart-incomplete'));
assert.ok(body.children[0].textContent.includes('未完成'));
assert.ok(nodes.get('candle-chart').children.some(node =>
  String(node.attrs.class).includes('chart-volume-unknown')), 'overflow is unknown, not zero');
draw('10.00', '11.00');
assert.ok(nodes.get('candle-chart').children.some(node =>
  node.attrs.class === 'chart-volume' && Number(node.attrs.height) === 70));
console.log('MARKET_CHART_PASS precision, direction, seven flat prices, finite coordinates, volume and partial bars');
