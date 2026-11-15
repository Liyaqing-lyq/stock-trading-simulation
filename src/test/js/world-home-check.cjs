const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element {
  constructor(tag='div'){this.tag=tag;this.children=[];this.attributes={};this.textContent='';this.value='';this.hidden=false;}
  append(...values){this.children.push(...values);}
  replaceChildren(...values){this.children=values;this.textContent='';}
  setAttribute(k,v){this.attributes[k]=v;}
  addEventListener(name,handler){(this.handlers??={})[name]=handler;}
  requestSubmit(){this.submitted=(this.submitted||0)+1;}
}
const nodes=new Map(),nav=new Map();
const html=fs.readFileSync('src/main/resources/web/index.html','utf8');
for(const [,id] of html.matchAll(/\bid="([^"]+)"/g)){assert(!nodes.has(id),'duplicate id: '+id);nodes.set(id,new Element());}
nodes.get('world-report-period').value='current';nodes.get('world-person-filter').value='all';
const context={window:{},Node:Element,document:{
  getElementById:id=>{assert(nodes.has(id),'missing id: '+id);return nodes.get(id);},
  createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),
  querySelectorAll:()=>[],querySelector:s=>{if(!nav.has(s))nav.set(s,new Element());return nav.get(s);}
}};
vm.runInNewContext(fs.readFileSync('src/main/resources/web/world-home.js','utf8'),context);
const statement={assets:{现金:'10.00'},liabilities:{},equity:{资本:'10.00'},income:{},expense:{},totalAssets:'10.00',totalLiabilities:'0.00',totalEquity:'10.00',profit:'0.00'};
const days=[{day:1,cash:'10.00',lastPrice:null,trades:0},{day:2,cash:'10.00',lastPrice:'9.90',close:'9.90',trades:1,volume:1,turnover:'9.90'},{day:3,cash:'10.00',lastPrice:'9.90',trades:0}];
const company={id:'one',name:'公司甲',business:'经营甲',prototype:'原型甲',boundary:'模拟边界',referencePrice:'10.00',lastPrice:'9.90',trades:1,volume:1,cash:'10.00',profit:'0.00',statement};
context.window.WorldHome.render({available:true,timeMillis:'259200000',companies:[company],people:[{id:'alice',name:'林知微',roles:['公司甲 · 董事'],system:false}],names:{},experiment:{days,note:'存档说明',details:{one:{openingCash:'10.00',cashChange:'0.00',days,periods:[]}}}});
const svg=nodes.get('world-price-chart').children[0];
assert.equal(svg.children.filter(n=>n.tag==='circle').length,1,'only actual trade days get dots');
assert.equal(svg.children.filter(n=>n.tag==='path').length,1,'no price before first trade');
assert.equal(svg.children.find(n=>n.tag==='path').attributes.class,'world-line carried','no-trade day must be dashed');
nodes.get('world-report-period').value='1';
// Refresh with an absent archived period must clear the prior financial/cash display.
context.window.WorldHome.render({available:true,timeMillis:'0',companies:[company],people:[],names:{}});
assert.equal(nodes.get('world-cash-chart').children.length,0);
assert.equal(nodes.get('world-cash-summary').textContent,'');
context.window.WorldHome.render({available:true,timeMillis:'172800000',companies:[company],people:[],names:{},behavior:{enabled:true,decisions:[{timeMillis:86400000,action:'WAIT',reason:'等待实际回款',results:[]}]}});
assert.equal(nodes.get('world-behavior').hidden,false);
nodes.get('world-behavior-day').handlers.click();
assert.equal(nodes.get('advance-target').value,'259200000');
assert.equal(nodes.get('advance-form').submitted,1,'advance uses the existing guarded form');
context.window.WorldHome.clear();
for(const [id,node] of nodes)if(id.startsWith('world-')&&!['world-report-period','world-person-filter','world-open-controls'].includes(id)){
  assert.equal(node.children.length,0,id+' retains hidden data');assert.equal(node.textContent,'',id+' retains hidden text');
}
console.log('world home: HTML ids, real trade gaps, unavailable periods, identity clearing PASS');
