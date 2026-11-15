const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element {
  constructor(tag='div'){this.tag=tag;this.children=[];this.attributes={};this.textContent='';this.value='';this.hidden=false;}
  append(...values){this.children.push(...values);}
  replaceChildren(...values){this.children=values;this.textContent='';}
  setAttribute(k,v){this.attributes[k]=v;}
  addEventListener(name,handler){(this.handlers??={})[name]=handler;}
  requestSubmit(){this.submitted=(this.submitted||0)+1;}
  scrollIntoView(){this.scrolled=true;}
}
const nodes=new Map(),nav=new Map();
const timers=new Map();let timerId=0;
const html=fs.readFileSync('src/main/resources/web/index.html','utf8');
for(const [,id] of html.matchAll(/\bid="([^"]+)"/g)){assert(!nodes.has(id),'duplicate id: '+id);nodes.set(id,new Element());}
nodes.get('world-report-period').value='current';nodes.get('world-person-filter').value='all';
const context={window:{},Node:Element,setTimeout:fn=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id),document:{
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
nodes.get('world-report-period').value='current';
const connectedView={available:true,runId:'connected-test',timeMillis:'1036800000',companies:[{...company,symbol:'ONE'}],people:[{id:'alice',name:'林知微',roles:['研究员']}],names:{alice:'林知微'},
  liveDetails:{one:{openingCash:'10.00',cashChange:'2.00',days:[{day:12,cash:'12.00',lastPrice:'10.50',close:'10.50',trades:2,volume:4,turnover:'42.00'}],periods:[]}},
  connected:{enabled:true,linkCounts:{publications:1,readings:2,research:1,orders:1,trades:2},
    activities:[{timeMillis:950400000,person:'alice',company:'one',action:'RESEARCH',reason:'读过交付公告后调整判断',sourceDoc:'news-one',status:'COMPLETED',details:{quantity:4}}],
    news:[{docId:'news-one',title:'实际交付公告',body:'<img src=x onerror=alert(1)>已交付4件',publisher:'alice',publishedAt:900000000}],
    peopleActivity:{alice:{action:'RESEARCH',reason:'读过交付公告后调整判断',timeMillis:950400000}},
    orders:[{symbol:'ONE',side:'BUY',quantity:4,filled:2,price:'10.50',status:'ACTIVE',accountId:'ACC-FUND'}]}};
context.window.WorldHome.render(connectedView);
assert.equal(nodes.get('world-price-chart').children[0].children.filter(n=>n.tag==='circle').length,1,'live prices render without an experiment attachment');
assert(!nodes.get('world-price-note').textContent.includes('季度'),'live market does not claim a fixed quarter');
const content=n=>[n.textContent,...n.children.map(content)].join(' ');
assert(content(nodes.get('world-activities')).includes('读过交付公告后调整判断'));
assert(content(nodes.get('world-person-cards')).includes('形成研究判断'));
assert(content(nodes.get('world-orders')).includes('尚有未成交委托'));
assert(content(nodes.get('world-news')).includes('<img src=x onerror=alert(1)>已交付4件'),'news text is retained literally');
assert.equal(nodes.get('world-news').children[0].children[1].tag,'p','news uses text nodes, not HTML');
const findButton=(node,label)=>node.children.flatMap(n=>[n,...findButton(n,label)]).filter(n=>n.tag==='button'&&n.textContent===label);
findButton(nodes.get('world-activities'),'查看消息原文')[0].handlers.click();
assert.equal(nodes.get('world-news').children[0].open,true,'source opens the actual cited document');
assert(nodes.get('world-price-chart').children[0].children.filter(n=>n.tag==='text').some(n=>n.textContent==='第12日'));
assert(!nodes.get('world-price-chart').children[0].children.filter(n=>n.tag==='text').some(n=>n.textContent==='第90日'));
assert(!nodes.get('world-hero-note').textContent.includes('一家'));
// Trading-role records have no manager company field; filtering must retain their real target links.
const linkedView={...connectedView,connected:{...connectedView.connected,activities:[
  {...connectedView.connected.activities[0],company:'',details:{symbol:'ONE',docId:'research-one'}},
  {timeMillis:950500000,person:'bob',action:'TARGET',sourceDoc:'research-one',reason:'关联研究目标',details:{symbol:'ONE',targetId:'target-one'}},
  {timeMillis:950600000,person:'carol',action:'REVIEW',reason:'关联目标风险审核',details:{targetId:'target-one'}},
  {timeMillis:950700000,person:'dave',action:'CHILD',reason:'其他公司委托',details:{symbol:'TWO'}}]}};
nodes.get('world-feed-company').value='one';context.window.WorldHome.render(linkedView);
assert(content(nodes.get('world-activities')).includes('读过交付公告后调整判断'));
assert(content(nodes.get('world-activities')).includes('关联目标风险审核'));
assert(!content(nodes.get('world-activities')).includes('其他公司委托'));
findButton(nodes.get('world-activities'),'查看研究引用原文')[0].handlers.click();
assert.equal(nodes.get('world-news').children[0].open,true);
nodes.get('world-feed-company').value='';context.window.WorldHome.render(connectedView);
const home=context.window.WorldHome;
const flush=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
  let calls=0,finish;
  home.onAdvance=target=>{calls++;return new Promise(resolve=>{finish=()=>{home.render({...connectedView,timeMillis:target});resolve();};});};
  nodes.get('world-connected-toggle').handlers.click();
  nodes.get('world-connected-day').handlers.click();
  assert.equal(calls,1,'only one advance can be outstanding');
  nodes.get('world-connected-toggle').handlers.click();finish();await flush();
  assert.equal(timers.size,0,'pause during an outstanding advance prevents the next day');
  assert.equal(nodes.get('world-connected-toggle').textContent,'开始运行');
  nodes.get('world-connected-toggle').handlers.click();finish();await flush();
  assert.equal(timers.size,1,'next day is scheduled only after completion');
  home.clear();assert.equal(timers.size,0,'identity clear cancels automatic stepping');
  for(const id of ['world-activities','world-news','world-connections','world-orders','world-person-cards'])assert.equal(content(nodes.get(id)).trim(),'','private god view removed: '+id);
  home.render(connectedView);nodes.get('world-connected-toggle').handlers.click();
  const late=finish;home.clear();late();await flush();
  assert.equal(timers.size,0,'a stale completion cannot restart the loop after identity change');
  home.render(connectedView);home.onAdvance=()=>Promise.reject(new Error('账户状态已改变'));
  nodes.get('world-connected-toggle').handlers.click();await flush();
  assert.equal(timers.size,0);assert(nodes.get('world-connected-status').textContent.includes('账户状态已改变'));
  home.clear();
  console.log('world home: trade gaps, live charts, source text, people/orders, single-flight/pause/error/identity clearing PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
