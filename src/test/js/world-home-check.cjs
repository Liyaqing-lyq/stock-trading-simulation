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
// Autonomous world: the same snapshot exchange must feed the market page without connected rules.
const autonomousDays=[
  {day:1,cash:null,lastPrice:null,close:null,trades:0,volume:0,turnover:'0.00'},
  {day:2,cash:null,lastPrice:'9.50',close:'9.50',trades:1,volume:5,turnover:'47.50'},
  {day:3,cash:null,lastPrice:'9.50',close:null,trades:0,volume:0,turnover:'0.00'}];
const manyOrders=[];
for(let i=1;i<=45;i++)manyOrders.push({orderId:i,symbol:'ONE',side:i%2?'SELL':'BUY',
  type:i===45?'MARKET':'LIMIT',price:i===45?null:'9.50',quantity:i===45?5:1,
  filled:i===44?0:1,status:i===45?'ACTIVE':i===44?'CANCELLED':'FILLED',accountId:'acc-one'});
const autonomousView={available:true,runId:'autonomy-market',timeMillis:'172800000',
  companies:[{...company,symbol:'ONE',lastPrice:'9.50',trades:1,volume:5}],people:[],
  names:{'acc-one':'机构投资账户'},
  liveDetails:{one:{openingCash:null,cashChange:null,days:autonomousDays,periods:[]}},
  marketOrders:{one:{total:45,rows:manyOrders.slice(-40)}}};
context.window.WorldHome.render(autonomousView);
assert.equal(nodes.get('world-orders-panel').hidden,false,'orders are visible without connected rules');
const autonomousRows=nodes.get('world-orders').children[1].children;
assert.equal(autonomousRows.length,40,'only the most recent 40 orders are shown');
assert.equal(autonomousRows[0].children[0].textContent,'机构投资账户','account is shown for each order');
assert.equal(autonomousRows[0].children[2].textContent,'市价','market orders show 市价 without inventing a price');
assert(nodes.get('world-orders-note').textContent.includes('45'),'total order count is visible');
assert(nodes.get('world-orders-note').textContent.includes('40'));
assert(content(nodes.get('world-orders')).includes('已撤销'),'cancelled orders remain visible');
assert(content(nodes.get('world-trade-days')).includes('47.50'),'turnover shows the real 5 × 9.50');
assert(content(nodes.get('world-trade-days')).includes('5 股'),'volume shows the real filled quantity');
assert.equal(nodes.get('world-trade-days').children[1].children.length,1,'blank days are not listed as trade days');
assert.equal(nodes.get('world-price-chart').children[0].children.filter(n=>n.tag==='circle').length,1,'blank days do not get price dots');
assert(nodes.get('world-price-note').textContent.includes('虚线'),'carried price is explained');
assert(!nodes.get('world-price-note').textContent.includes('季度'));
assert(nodes.get('world-cash-summary').textContent.includes('当前现金'),'unknown opening cash is not zero-filled');
assert(!nodes.get('world-cash-summary').textContent.includes('开局现金'));
// An older attachment can supply historical cash observations while the live exchange owns current trades.
context.window.WorldHome.render({...autonomousView,experiment:{days:[],details:{
  one:{openingCash:'10.00',cashChange:'0.00',days:[],periods:[]}}}});
assert(content(nodes.get('world-trade-days')).includes('47.50'));
assert(nodes.get('world-cash-summary').textContent.includes('10.00'));
// No trades but a real open order: the order table must stay visible and the chart must stay empty.
const noTradeView={...autonomousView,runId:'autonomy-open-orders',
  companies:[{...company,symbol:'ONE',lastPrice:null,trades:0,volume:0}],
  liveDetails:{one:{openingCash:null,cashChange:null,periods:[],
    days:[{day:1,cash:null,lastPrice:null,close:null,trades:0,volume:0,turnover:'0.00'}]}},
  marketOrders:{one:{total:1,rows:[{orderId:1,symbol:'ONE',side:'BUY',type:'LIMIT',price:'9.50',
    quantity:5,filled:0,status:'ACTIVE',accountId:'acc-one'}]}}};
context.window.WorldHome.render(noTradeView);
assert.equal(nodes.get('world-orders-panel').hidden,false,'open orders keep the table visible even with no trades');
assert.equal(nodes.get('world-price-chart').children[0].tag,'p','no trades means an empty price chart');
assert(content(nodes.get('world-price-chart')).includes('尚无实际成交'));
assert.equal(nodes.get('world-trade-days').children[1].children.length,0);
assert(content(nodes.get('world-quote')).includes('暂无成交'));
const home=context.window.WorldHome;
const read={person:'alice',at:'21'},judgment={person:'alice',id:'private-judgment',at:'22',rationale:'读后预期'};
home.render({...connectedView,companies:[{...company,id:'one',symbol:'ONE',operationsPage:'operations',publications:[{
  kind:'订单公告',title:'第一份订单披露',docId:'report-one',version:1,time:'19',body:'原文',publisher:'alice',reads:[read],beliefs:[judgment]}]},
  {...company,id:'two',symbol:'TWO',operationsPage:'operations',publications:[]}]});
let opened='';
home.onOperations=id=>opened=id;
findButton(nodes.get('world-report-links'),'产品、订单与回款')[0].handlers.click();
assert.equal(opened,'one');
assert(content(nodes.get('world-report-sources')).includes('private-judgment'));
nodes.get('world-company-select').handlers.change({target:{value:'two'}});
assert(!content(nodes.get('world-report-sources')).includes('report-one'));
findButton(nodes.get('world-report-links'),'产品、订单与回款')[0].handlers.click();
assert.equal(opened,'two');
home.onPage=page=>opened=page;
findButton(nodes.get('world-report-links'),'TWO · 委托与成交')[0].handlers.click();
assert.equal(opened,'world-market');
home.clear();
assert.equal(content(nodes.get('world-report-sources')).trim(),'');
nodes.get('world-report-period').value='1';
home.render({...connectedView,connected:null,marketOrders:{one:{rows:[],total:0}},
  liveDetails:{one:{days:[],periods:[],openingCash:null,cashChange:null}},
  experiment:{details:{one:{days:[],periods:[{period:1,day:30,statement}],openingCash:'9991',cashChange:'9981'}}}});
assert.equal(nodes.get('world-report-period').value,'current','unavailable archived periods are not presented as real');
assert(!nodes.get('world-cash-summary').textContent.includes('9,991'),'autonomous company must only use this run');home.clear();
const flush=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
  const cut={period:"6",capturedAt:"15552000000",position:"91",digest:"frozen-six"};
  const archive={...connectedView,connected:null,runId:"period-test",marketOrders:{},
    companies:[{...company,frozenPeriods:[cut]}]};
  const response={companyPartyId:company.id,period:6,capturedAt:15552000000,position:91,statementDigest:"frozen-six",body:"本期实际现金=9168.00"};
  home.render(archive);
  assert.equal(nodes.get("world-report-period").hidden,true);
  let release;
  home.onPeriodStatement=()=>new Promise(resolve=>release=resolve);
  const lateRead=findButton(nodes.get("world-report-sources"),"查看期末报表")[0].handlers.click();
  home.clear();release(response);await lateRead;
  assert.equal(content(nodes.get("world-report-sources")).trim(),"");
  home.render(archive);
  home.onPeriodStatement=async()=>({...response,statementDigest:"other"});
  await findButton(nodes.get("world-report-sources"),"查看期末报表")[0].handlers.click();
  assert(!content(nodes.get("world-report-sources")).includes("9168.00"));
  home.onPeriodStatement=async()=>response;
  await findButton(nodes.get("world-report-sources"),"查看期末报表")[0].handlers.click();
  assert(content(nodes.get("world-report-sources")).includes("本期实际现金=9168.00"));
  home.clear();
  const live={available:true,runId:'active-governance',timeMillis:'1',position:'40',people:[],names:{director:'林知微'},
    companies:[{...company,governance:{meetings:2,budgetLines:0,managers:['director']}}],
    modelAttempts:71,autonomy:{available:true,running:true,reason:'RUNNING',cycles:1,calls:1,companies:1,persons:1,timeMillis:'1',position:'40'}};
  home.render(live);
  assert(content(nodes.get('world-company-list')).includes('林知微'),'active manager must come from the current snapshot');
  assert(content(nodes.get('world-company-list')).includes('2 场会议'));
  assert(content(nodes.get('world-metrics')).includes('71'),'restored run retains cumulative model attempts');
  let selectedCompany;
  home.onGovernance=id=>selectedCompany=id;
  findButton(nodes.get('world-company-list'),'查看治理')[0].handlers.click();
  assert.equal(selectedCompany,'one','governance link opens the selected company');
  home.onAutonomy=async()=>({...live.autonomy,position:'41'});
  home.onOverview=async()=>({...live,position:'41',autonomy:{...live.autonomy,position:'41'},companies:[{...company,governance:{meetings:3,budgetLines:1,managers:[]}}]});
  const [pollId,poll]=[...timers.entries()][0];timers.delete(pollId);await poll();
  assert(content(nodes.get('world-company-list')).includes('3 场会议'),'running snapshot refreshes before the run stops');
  assert(content(nodes.get('world-company-list')).includes('经理空缺'));
  const [lateId,latePoll]=[...timers.entries()][0];timers.delete(lateId);
  let resolveView;
  home.onAutonomy=async()=>({...live.autonomy,position:'42'});
  home.onOverview=()=>new Promise(resolve=>resolveView=resolve);
  const stale=latePoll();await flush();home.clear();resolveView(live);await stale;
  assert.equal(content(nodes.get('world-company-list')).trim(),'','late poll cannot display admin data after leaving');
  home.render(connectedView);
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
