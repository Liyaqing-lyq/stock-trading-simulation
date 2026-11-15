"use strict";
window.WorldHome = (() => {
  let view = null, selected = "", initialized = false;
  const $ = id => document.getElementById(id);
  const element = (tag, value, className) => { const n=document.createElement(tag); if(value!=null)n.textContent=String(value); if(className)n.className=className; return n; };
  const money = value => value == null ? "暂无成交" : Number(value).toLocaleString("zh-CN", {minimumFractionDigits:2,maximumFractionDigits:2});
  const detail = () => view?.experiment?.details?.[selected];
  const company = () => view?.companies.find(c=>c.id===selected);
  const sign = value => Number(value)>0 ? "positive" : Number(value)<0 ? "negative" : "";
  const labels = {RESERVE:"结算准备金",PREMISES:"营业设施","INT.RECV":"应收利息",PAIDIN:"实收资本",RETAINED:"留存收益",AT1:"其他一级资本","DISCHARGE.EQUITY":"债务解除权益","BORROW.FAC":"借款本金","INT.PAYABLE.FAC":"应付借款利息",SUBT2:"二级资本工具","INT.PAYABLE":"应付存款利息","INT.INC":"利息收入","FEE.INC":"手续费收入",RECOVERY:"已核销款回收","GAIN.WAIVE":"债务豁免收益","GAIN.ASSIGN":"转让收益","GAIN.DISPOSAL":"处置收益","INT.EXP":"存款利息支出","INT.EXP.FAC":"融资利息支出","FEE.EXP":"手续费支出",IMPAIR:"信用减值损失","LOSS.ASSIGN":"转让损失","LOSS.DISPOSAL":"处置损失"};
  function accountLabel(key) {
    if(labels[key]) return labels[key];
    for(const [prefix,suffix] of [["DEPOSIT-","存款负债"],["dep-","存款负债"],["book-","贷款账面余额"],["impair-","贷款减值准备"]])
      if(key.startsWith(prefix)){const id=key.slice(prefix.length);return (view?.names?.[id]||({"eng-a-cus":"工程客户","eng-a-sup":"工程供应商","eng-a-ops":"工程作业方"})[id]||id)+" · "+suffix;}
    return key;
  }
  function choose(id, page) { selected=id; paint(); if(page) api.onPage?.(page); }
  function button(label, action) {const n=element("button",label,"world-link");n.type="button";n.addEventListener("click",action);return n;}
  function metric(label,value,extra="") {const n=element("div",null,"world-metric");n.append(element("span",label),element("strong",value),element("small",extra));return n;}
  function table(target, columns, rows) {
    const node=$(target); node.replaceChildren();
    const head=element("tr");columns.forEach(c=>head.append(element("th",c)));const thead=element("thead");thead.append(head);node.append(thead);
    const body=element("tbody");for(const row of rows){const tr=element("tr");for(const value of row){const td=element("td");if(value instanceof Node)td.append(value);else td.textContent=String(value??"—");tr.append(td);}body.append(tr);}node.append(body);
  }
  function chart(target, rows, field, priceMode) {
    const wrap=$(target);wrap.replaceChildren();
    const valid=rows.filter(r=>r[field]!=null && Number.isFinite(Number(r[field])));
    if(!valid.length){wrap.append(element("p",priceMode?"尚无实际成交，不能生成价格曲线。":"尚无逐日现金记录；当前余额见上方报表。","world-empty"));return;}
    const values=valid.map(r=>Number(r[field]));let low=Math.min(...values),high=Math.max(...values);
    const pad=Math.max((high-low)*.15,Math.abs(high)*.015,.05);low-=pad;high+=pad;
    const ns="http://www.w3.org/2000/svg",svg=document.createElementNS(ns,"svg");svg.setAttribute("viewBox","0 0 900 260");svg.setAttribute("role","img");svg.setAttribute("aria-label",company().name+(priceMode?"季度实际成交价与无成交时段":"每日现金变化"));
    const add=(tag,attrs,text)=>{const n=document.createElementNS(ns,tag);Object.entries(attrs).forEach(([k,v])=>n.setAttribute(k,String(v)));if(text!=null)n.textContent=text;svg.append(n);return n;};
    const x=d=>64+(d-1)*808/89,y=p=>216-(Number(p)-low)*184/(high-low);
    for(let i=0;i<3;i++){const p=low+(high-low)*i/2;add("line",{x1:64,x2:872,y1:y(p),y2:y(p),class:"world-grid-line"});add("text",{x:54,y:y(p)+4,"text-anchor":"end",class:"world-axis"},money(p));}
    for(const d of [1,30,60,90])add("text",{x:x(d),y:247,"text-anchor":"middle",class:"world-axis"},"第"+d+"日");
    for(let i=1;i<valid.length;i++){
      const a=valid[i-1],b=valid[i];
      add("path",{d:priceMode?`M${x(a.day)} ${y(a[field])} H${x(b.day)} V${y(b[field])}`:`M${x(a.day)} ${y(a[field])} L${x(b.day)} ${y(b[field])}`,class:priceMode&&!b.trades?"world-line carried":"world-line"});
    }
    for(const r of valid) if(!priceMode||r.trades){const dot=add("circle",{cx:x(r.day),cy:y(r[field]),r:priceMode?3.2:1.8,class:"world-dot"});const title=document.createElementNS(ns,"title");title.textContent=`第${r.day}日 · ${money(r[field])}`+(priceMode?` · ${r.trades}笔 / ${r.volume}股`:"");dot.append(title);}
    wrap.append(svg);
  }
  function renderOverview() {
    const exp=view.experiment,days=exp?.days||[],trades=view.companies.reduce((a,c)=>a+c.trades,0);
    $("world-day").textContent="第 "+Math.floor(Number(view.timeMillis)/86400000)+" 个实验日";
    $("world-hero-note").textContent=exp?"已完成一个季度。查看公司的真实经营、市场成交和人物任职。":"从公司的经营开始，观察信息、人物和交易如何相互影响。";
    $("world-metrics").replaceChildren(metric("公司",view.companies.length,"十种经营机制"),metric("真实成交",trades,"来自实际撮合"),view.behavior?.enabled?metric("交易行为","待整改","本轮验证制造经营闭环"):metric("有成交的日子",days.filter(d=>d.trades>0).length+" / "+days.length,"无成交日不补造价格"),metric("人物",view.people.filter(p=>!p.system).length,"另有研发试验系统"));
    table("world-company-list",["公司","最近成交价","累计成交量","现金 / 准备金","累计损益","查看"],view.companies.map(c=>[
      button(c.name,()=>choose(c.id,"world-companies")),c.lastPrice==null?"尚无成交（参考 "+money(c.referencePrice)+"）":money(c.lastPrice),c.volume+" 股",money(c.cash),element("span",money(c.profit),sign(c.profit)),button("股价与成交",()=>choose(c.id,"world-market"))]));
    $("world-run-note").textContent=exp?.note||"当前公司状态来自同一已确认世界时点。";
    $("world-behavior").hidden=!view.behavior?.enabled;
    if(view.behavior?.enabled){
      const actions={SIGN_CONTRACT:"接受订单",ORDER_MATERIAL:"采购缺料",START_BATCH:"安排生产",DELIVER:"交付产品",COLLECT:"收取到期货款",MARK_OVERDUE:"记录履约逾期",WAIT:"等待"};
      $("world-hero-note").textContent="行为整改实验：先验证一家制造公司的持续经营。其余公司和市场行为仍待修正。";
      $("world-behavior-note").textContent="客户使用有限的开局资金，不自动补款；生产须有订单，采购和生产须有资金与授权。下表显示最近业务动作及当前等待原因。";
      const decisions=view.behavior.decisions.filter(d=>d.action!=="WAIT");
      const latest=view.behavior.decisions.at(-1);if(latest?.action==="WAIT")decisions.push(latest);
      table("world-behavior-decisions",["实验时点","决定","规模","依据","实际结果"],decisions.slice(-12).reverse().map(d=>[
        "第 "+Math.floor(Number(d.timeMillis)/86400000)+" 日 "+Math.floor(Number(d.timeMillis)%86400000/3600000)+" 时",
        actions[d.action]||d.action,d.fields?.quantity!=null?d.fields.quantity+(d.action==="ORDER_MATERIAL"?" 单位原料":" 件产品"):
          d.action==="COLLECT"&&d.results.some(r=>r.accepted)?money(d.results.filter(r=>r.accepted).reduce((sum,r)=>sum+Number(r.amount),0))+" 元":"—",
        d.limitingFactor||d.reason,d.action==="WAIT"?"未新增业务":d.results.length?d.results.map(r=>r.accepted?"已执行" : "未执行："+r.reason).join("；"):"待执行"]));
    }
  }
  function renderMarket() {
    const c=company(),d=detail();$("world-market-name").textContent=c.name;$("world-market-business").textContent=c.business;
    const change=c.lastPrice==null?null:(Number(c.lastPrice)/Number(c.referencePrice)-1)*100;
    $("world-quote").replaceChildren(metric("最近成交价",money(c.lastPrice),"开局参考价 "+money(c.referencePrice)),metric("相对开局参考",change==null?"—":(change>=0?"+":"")+change.toFixed(2)+"%","不代表实际投资收益"),metric("累计成交量",c.volume+" 股",c.trades+" 笔成交"));
    chart("world-price-chart",d?.days||[],"lastPrice",true);
    $("world-price-note").textContent=d?"第1–90日季度记录。圆点是有实际成交的日子；虚线只延续上次成交价，不代表该日发生成交。没有第一笔成交前不画价格。":"此运行尚未挂载季度每日记录。";
    table("world-trade-days",["实验日","收盘成交价","成交笔数","成交量","成交额"],(d?.days||[]).filter(r=>r.trades>0).map(r=>["第"+r.day+"日",money(r.close),r.trades,r.volume+" 股",money(r.turnover)]));
  }
  function section(title,values,period=false) {
    const box=element("section",null,"world-statement-section");box.append(element("h3",title));
    const dl=element("dl");for(const [key,value] of Object.entries(values||{})){const label=accountLabel(key);dl.append(element("dt",period?label.replace(/（累计）$/,"（本期）"):label),element("dd",money(value)));}box.append(dl);return box;
  }
  function renderCompany() {
    const c=company(),d=detail(),period=$("world-report-period").value;
    const item=period==="current"?null:d?.periods.find(p=>String(p.period)===period);
    const report=item?.statement||(period==="current"?c.statement:null);
    $("world-report-name").textContent=c.name;$("world-report-description").textContent=c.business+" · 机制原型："+c.prototype;
    $("world-report-cut").textContent=item?`第${item.period}期 · 第${item.day}日末；利润表为本期发生额，资产负债表为期末余额。`:"当前已确认状态 · 利润表为从开局至今的累计额。";
    if(view.behavior?.enabled&&!item&&c.id==="mfg-tech")$("world-report-cut").textContent+=" 本模型固定费用按30日关期入账，期中利润尚未包含本期固定费用；客户报价和成本规模仍为未校准实验值。";
    for(const id of ["world-balance","world-income","world-report-metrics","world-cash-chart","world-boundaries"])$(id).replaceChildren();
    $("world-cash-summary").textContent="";
    if(!report){$("world-balance").append(element("p","这份期末报表尚不可用。"));return;}
    const profit=item?report.profitPeriod:report.profit;
    $("world-report-metrics").append(metric("总资产",money(report.totalAssets)),metric("总负债",money(report.totalLiabilities)),metric("净资产",money(report.totalEquity)),metric(item?"本期利润":"累计利润",money(profit)));
    $("world-balance").append(section("资产",report.assets));
    if(Object.keys(report.contra||{}).length)$("world-balance").append(section("减：资产减值准备",report.contra));
    $("world-balance").append(section("负债",report.liabilities),section("所有者权益",report.equity));
    $("world-income").append(section("收入",item?report.incomePeriod:report.income,!!item),section("费用",item?report.expensePeriod:report.expense,!!item),section(item?"本期结果":"累计结果",{"利润":profit}));
    $("world-cash-summary").textContent=d?"季度开局现金 / 准备金 "+money(d.openingCash)+" → 第90日 "+money(d.days.at(-1).cash)+"，净变动 "+money(d.cashChange)+"。现金变化不等于利润。":"当前现金 / 准备金 "+money(c.cash);
    chart("world-cash-chart",d?.days||[],"cash",false);
    $("world-boundaries").replaceChildren(element("p",c.boundary));(c.notes||[]).forEach(n=>$("world-boundaries").append(element("p",n)));
  }
  function renderPeople() {
    const query=$("world-person-search").value.trim(),filter=$("world-person-filter").value;
    const people=view.people.filter(p=>(!query||(p.name+p.roles.join(" ")).includes(query))&&(filter==="all"||(filter==="manager"&&p.roles.some(r=>r.endsWith("总经理")))||(filter==="director"&&p.roles.some(r=>r.endsWith("董事")))||(filter==="market"&&p.roles.some(r=>/研究员|投资经理|风控|交易员|记者|编辑/.test(r)))));
    $("world-person-count").textContent=people.length+" 个可见条目 · 中文姓名为虚构显示名，身份与任职记录保持原样。";
    const grid=$("world-person-cards");grid.replaceChildren();for(const p of people){
      const card=element("article",null,"world-person-card"),avatar=element("span",p.name.slice(0,1),"world-avatar");
      card.append(avatar,element("h3",p.name));
      if(p.system)card.append(element("p","研发试验结果执行系统，不是自主人物。"));
      else (p.roles.length?p.roles:[p.id==="investor"?"独立投资者":p.id==="nina"||p.id==="omar"?"普通职员":"当前无有效任职"]).forEach(role=>card.append(element("p",role)));
      const id=element("details");id.append(element("summary","查看身份编号"),element("code",p.id));card.append(id);
      if(!p.system)card.append(button("进入人物视角",()=>api.onPerson?.(p.id)));grid.append(card);
    }
  }
  function paint() {
    if(!view?.available)return;
    for(const id of ["world-market-select","world-company-select"]){const select=$(id);select.replaceChildren();for(const c of view.companies){const o=element("option",c.name);o.value=c.id;select.append(o);}select.value=selected;}
    renderOverview();renderMarket();renderCompany();renderPeople();
  }
  const api={onPage:null,onPerson:null,
    render(value){
      view=value?.available?value:null;document.querySelectorAll("[data-world-nav]").forEach(n=>n.hidden=!view);
      $("world-home").hidden=!view;
      if(!view){api.clear();return;}
      for(const page of ["market","company","org","people"])document.querySelector(`[data-page="${page}"]`).hidden=true;
      if(!view.companies.some(c=>c.id===selected))selected=view.behavior?.enabled&&view.companies.some(c=>c.id==="mfg-tech")?"mfg-tech":view.companies[0].id;
      if(!initialized){$("overview-audit").open=false;initialized=true;}
      $("context-time").textContent="第 "+Math.floor(Number(view.timeMillis)/86400000)+" 个实验日 · "+view.companies.length+" 家公司";
      paint();
    },
    clear(){
      view=null;selected="";initialized=false;
      $("world-behavior-decisions").replaceChildren();$("world-behavior-note").textContent="";$("world-behavior").hidden=true;
      for(const id of ["world-company-list","world-person-cards","world-balance","world-income","world-price-chart","world-trade-days","world-cash-chart","world-report-metrics","world-metrics","world-quote","world-boundaries","world-market-select","world-company-select"])$(id)?.replaceChildren();
      for(const id of ["world-run-note","world-price-note","world-day","world-hero-note","world-report-name","world-report-description","world-report-cut","world-cash-summary","world-person-count","world-market-name","world-market-business"])if($(id))$(id).textContent="";
      $("world-person-search").value="";$("world-home").hidden=true;
      document.querySelectorAll("[data-world-nav]").forEach(n=>n.hidden=true);
      for(const page of ["market","company","org","people"]){const n=document.querySelector(`[data-page="${page}"]`);if(n)n.hidden=false;}
    }
  };
  $("world-market-select").addEventListener("change",e=>choose(e.target.value));$("world-company-select").addEventListener("change",e=>choose(e.target.value));
  $("world-report-period").addEventListener("change",()=>view&&renderCompany());
  $("world-person-search").addEventListener("input",()=>view&&renderPeople());$("world-person-filter").addEventListener("change",()=>view&&renderPeople());
  $("world-open-controls").addEventListener("click",()=>{$("overview-audit").open=true;$("advance-form").scrollIntoView({behavior:"smooth",block:"center"});});
  $("world-behavior-day").addEventListener("click",()=>{
    if(!view?.behavior?.enabled)return;
    $("advance-target").value=String(BigInt(view.timeMillis)+86400000n);
    $("advance-form").requestSubmit();
  });
  return api;
})();
