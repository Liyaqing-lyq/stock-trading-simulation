// Executes the actual route form load/save functions with a small DOM fixture.
// HTTP roundtrip/permissions are separately covered by WorldWebRoutesTest.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/web/app.js'), 'utf8');
const segment = source.slice(source.indexOf('function routeField('), source.indexOf('function svgNode('));
assert.ok(segment.includes('async function saveRoutes('));
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.dataset = {}; }
  append(...items) { this.children.push(...items); }
  appendChild(item) { this.children.push(item); return item; }
  replaceChildren(...items) { this.children = items; }
  querySelectorAll() { return this.children.flatMap(item => [...(item.dataset?.field ? [item] : []), ...(item.querySelectorAll ? item.querySelectorAll() : [])]); }
}
const elements = new Map(['route-provider', 'route-runtime', 'routes-list'].map(id => [id, new Element('div')]));
const policy = 'deepseek-thinking-high-v1';
let backend = {revision: '3', baseUrl: 'https://api.deepseek.com', connected: false, routes:
  ['governance','research','trading','media','evaluation'].map((scene,index) => ({scene,slotId:'key-'+(index+1),model:'deepseek-flash',enabled:true,maxCalls:2500,maxTotalTokens:35000000,maxOutputTokens:8192,maxConcurrency:4,budgetPolicy:'lifecycle-v1',...(index===0?{providerPolicy:policy}:{})}))};
let posted;
const context = vm.createContext({
  document: {createElement: tag => new Element(tag), createTextNode: value => ({textContent:value})},
  $: id => elements.get(id), state: {mode:'admin', routeRevision:null, generation:1},
  isCurrent: generation => generation===1, refreshCapabilities: async () => true,
  setMessage: () => {}, sessionLost: () => {throw new Error('Unexpected lost session');},
  request: async (method, url, body) => {
    assert.equal(url, '/api/admin/routes');
    if(method==='POST') {posted=JSON.parse(JSON.stringify(body));backend={...backend,revision:String(Number(backend.revision)+1),routes:posted.routes};}
    return {status:200,payload:JSON.parse(JSON.stringify(backend))};
  }
});
vm.runInContext(segment,context);
const controls = index => elements.get('routes-list').children[index].querySelectorAll();
const field = (name,index=0) => controls(index).find(control=>control.dataset.field===name);
(async () => {
  await context.refreshRoutes(1);
  assert.equal(field('providerPolicy').value,policy);
  assert.deepEqual(field('providerPolicy').children.map(option=>option.textContent),['关闭','开启 (high)']);
  assert.equal(field('maxCalls').max,5000);assert.equal(field('maxTotalTokens').max,100000000);
  await context.saveRoutes({preventDefault(){}});
  assert.equal(posted.routes[0].providerPolicy,policy);assert.equal(posted.routes[0].budgetPolicy,'lifecycle-v1');
  assert.equal(posted.routes[0].maxCalls,2500);assert.equal(posted.routes[0].maxOutputTokens,8192);
  assert.ok(!Object.hasOwn(posted.routes[1],'providerPolicy'));
  field('providerPolicy').value='';await context.saveRoutes({preventDefault(){}});
  assert.ok(!Object.hasOwn(posted.routes[0],'providerPolicy'));assert.equal(posted.routes[0].budgetPolicy,'lifecycle-v1');
  assert.equal(field('providerPolicy').value,'');
  field('providerPolicy').value=policy;await context.saveRoutes({preventDefault(){}});
  assert.equal(posted.routes[0].providerPolicy,policy);assert.equal(field('providerPolicy').value,policy);
  console.log('PASS actual route form: enabled/high load/save, off omission, legacy omission, lifecycle budget preservation');
})().catch(error=>{console.error(error);process.exitCode=1;});
