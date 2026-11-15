"use strict";
window.WorldHome = (() => {
  let view = null, selected = "", initialized = false, running = false, stepping = false, runTimer = null, generation = 0;
  let selectedSource = "";
  const $ = id => document.getElementById(id);
  const element = (tag, value, className) => { const n=document.createElement(tag); if(value!=null)n.textContent=String(value); if(className)n.className=className; return n; };
  const money = value => value == null ? "暂无成交" : Number(value).toLocaleString("zh-CN", {minimumFractionDigits:2,maximumFractionDigits:2});
  const detail = () => view?.liveDetails?.[selected] || view?.experiment?.details?.[selected];
  const company = () => view?.companies.find(c=>c.id===selected);
  const personName = id => view?.names?.[id] || id || "世界";
  const companyName = id => view?.companies.find(c=>c.id===id)?.name || id || "市场";
  function activityCompany(activity) {
    if(activity.company)return activity.company;
    const target=activity.details?.targetId&&(view.connected?.activities||[]).find(a=>a.action==="TARGET"&&a.details?.targetId===activity.details.targetId);
    const symbol=activity.details?.symbol||target?.details?.symbol,source=activity.sourceDoc||target?.sourceDoc||"";
    const news=(view.connected?.news||[]).find(n=>n.docId===source);
    return view.companies.find(c=>c.symbol===symbol||source.startsWith("connected-news:"+c.id+":")||source.startsWith("research:connected-news:"+c.id+":")||news?.title==="经营观察 · "+c.name)?.id||"";
  }
  const at = value => "第 "+Math.floor(Number(value)/86400000)+" 日 "+Math.floor(Number(value)%86400000/3600000)+" 时";
  const actionNames = {SIGN_CONTRACT:"接受订单",ORDER_MATERIAL:"采购缺料",START_BATCH:"安排生产",DELIVER:"交付产品",COLLECT:"收取到期货款",MARK_OVERDUE:"记录履约逾期",WAIT:"等待",PUBLISH:"发布经营快报",DISCLOSE:"发布经营快报",READ:"阅读消息",READING:"阅读消息",RESEARCH:"形成研究判断",TARGET:"调整投资目标",REVIEW:"审核风险",CONFIRM:"确认投资目标",CHILD:"提交执行委托",ORDER:"提交委托",BUY:"买入委托",SELL:"卖出委托",CANCEL:"撤回委托",POST:"发布观点",MEDIA:"发表报道",BEGIN_EXTRACTION:"安排采掘",BEGIN_POWER:"安排发电",DISPATCH_FLOW:"调整发电流量",BEGIN_PILOT:"开始试验",BEGIN_VALIDATION:"开始验证",APPROVE_COMMERCIAL:"批准商业化",ORDER_RAW:"采购原料",RECEIVE_RAW:"接收原料",PRODUCE:"安排生产",REGISTER:"研发立项",BEGIN:"开始研发阶段",SIGN:"签订工程合同",START_WORK:"安排工程履约",INVOICE:"开具工程账单"};
  const actionName = action => actionNames[action] || actionNames[String(action).split(":").at(-1)] || ({RESEARCH_REGISTER:"研发立项",RESEARCH_BEGIN:"开始研发阶段"})[action] || action || "等待";
  const statusName = status => ({COMPLETED:"已完成",EXECUTED:"已执行",ACCEPTED:"已受理",SCHEDULED:"待执行",PENDING:"待执行",WAIT:"等待",ACTIVE:"尚有未成交委托",FILLED:"全部成交",PARTIALLY_FILLED:"部分成交",CANCELLED:"已撤销",REJECTED:"未执行",EXPIRED:"已过期",FAILED:"执行失败","1|REGISTERED":"已完成立项","1|STARTED":"已开始研究"})[status] || status || "尚无执行回报";
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
    const ns="http://www.w3.org/2000/svg",svg=document.createElementNS(ns,"svg");svg.setAttribute("viewBox","0 0 900 260");svg.setAttribute("role","img");svg.setAttribute("aria-label",company().name+(priceMode?"实际成交价与无成交时段":"实际现金观察记录"));
    const add=(tag,attrs,text)=>{const n=document.createElementNS(ns,tag);Object.entries(attrs).forEach(([k,v])=>n.setAttribute(k,String(v)));if(text!=null)n.textContent=text;svg.append(n);return n;};
    const first=Math.min(...rows.map(r=>Number(r.day))),last=Math.max(...rows.map(r=>Number(r.day)));
    const x=d=>last===first?468:64+(d-first)*808/(last-first),y=p=>216-(Number(p)-low)*184/(high-low);
    for(let i=0;i<3;i++){const p=low+(high-low)*i/2;add("line",{x1:64,x2:872,y1:y(p),y2:y(p),class:"world-grid-line"});add("text",{x:54,y:y(p)+4,"text-anchor":"end",class:"world-axis"},money(p));}
    for(const d of [...new Set([first,Math.round(first+(last-first)/3),Math.round(first+2*(last-first)/3),last])])add("text",{x:x(d),y:247,"text-anchor":"middle",class:"world-axis"},"第"+d+"日");
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
    $("world-metrics").replaceChildren(metric("公司",view.companies.length,"经营与财务分别记账"),metric("真实成交",trades,"来自实际撮合"),view.connected?.enabled?metric("实际阅读",view.connected.linkCounts?.readings??0,"消息读完后才进入判断"):view.behavior?.enabled?metric("交易行为","待整改","本轮验证制造经营闭环"):metric("有成交的日子",days.filter(d=>d.trades>0).length+" / "+days.length,"无成交日不补造价格"),metric("人物",view.people.filter(p=>!p.system).length,"另有研发试验系统"));
    table("world-company-list",["公司","最近成交价","累计成交量","现金 / 准备金","累计损益","查看"],view.companies.map(c=>[
      button(c.name,()=>choose(c.id,"world-companies")),c.lastPrice==null?"尚无成交（参考 "+money(c.referencePrice)+"）":money(c.lastPrice),c.volume+" 股",money(c.cash),element("span",money(c.profit),sign(c.profit)),button("股价与成交",()=>choose(c.id,"world-market"))]));
    $("world-run-note").textContent=exp?.note||"当前公司状态来自同一已确认世界时点。";
    $("world-behavior").hidden=!view.behavior?.enabled||!!view.connected?.enabled;
    if(view.behavior?.enabled&&!view.connected?.enabled){
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
    renderConnected();
  }
  function runControls(message) {
    $("world-connected-toggle").textContent=running?"暂停运行":"开始运行";
    $("world-connected-toggle").disabled=stepping&&!running;
    $("world-connected-day").disabled=stepping||running;
    if(message!=null)$("world-connected-status").textContent=message;
  }
  function stopRunning(message) {
    running=false;clearTimeout(runTimer);runTimer=null;
    runControls(message);
  }
  async function advanceDay() {
    if(stepping||!view?.connected?.enabled)return;
    const currentGeneration=generation, before=BigInt(view.timeMillis);
    stepping=true;runControls("正在处理这一天的经营、消息和订单…");
    try {
      if(typeof api.onAdvance!=="function")throw new Error("运行入口尚未连接，请刷新页面。");
      await api.onAdvance(String(before+86400000n));
      if(currentGeneration!==generation)return;
      if(!view?.connected?.enabled||BigInt(view.timeMillis)<=before)throw new Error("世界时间未推进，已停止连续运行。请查看运行详情。");
      runControls(running?"本日已完成，稍后继续下一日。":"已完成当前一天，世界已暂停。");
    } catch(error) {
      if(currentGeneration===generation)stopRunning("运行已停止："+(error?.message||String(error)));
    } finally {
      if(currentGeneration===generation){
        stepping=false;runControls();
        if(running)runTimer=setTimeout(()=>{runTimer=null;void advanceDay();},2000);
      }
    }
  }
  function renderConnected() {
    const data=view.connected;$("world-connected").hidden=!data?.enabled;
    if(!data?.enabled){if(running)stopRunning();return;}
    $("world-hero-note").textContent="经营结果成为消息，人物读到后形成判断，再通过真实订单影响市场。下方可以查看每一步已经发生的事情。";
    $("world-run-note").textContent="当前经营、信息、订单与财务来自同一世界时点。规则和参数为实验设定，实际等待、拒绝和未成交会保留。";
    runControls();if(!$("world-connected-status").textContent)runControls("已暂停。可以运行一天，也可以开始连续运行；关闭页面后不会继续自动推进。");
    const counts=data.linkCounts||{};
    $("world-connections").replaceChildren(...[["公开消息",counts.publications],["实际阅读",counts.readings],["研究判断",counts.research],["委托",counts.orders],["真实成交",counts.trades]].map(([label,value])=>metric(label,value??0,"本世界累计记录")));
    const filter=$("world-feed-company"),previous=filter.value;
    filter.replaceChildren(element("option","全部公司"));filter.children[0].value="";
    for(const c of view.companies){const option=element("option",c.name);option.value=c.id;filter.append(option);}filter.value=previous;
    const activities=(data.activities||[]).filter(a=>!filter.value||activityCompany(a)===filter.value).slice().sort((a,b)=>Number(b.timeMillis)-Number(a.timeMillis)).slice(0,40);
    const feed=$("world-activities");feed.replaceChildren();
    if(!activities.length)feed.append(element("p","当前筛选范围尚无决定记录。运行世界后，实际产生的活动会显示在这里。","world-empty"));
    for(const a of activities){
      const row=element("article",null,"world-activity"),heading=element("div",null,"world-activity-heading");
      heading.append(element("time",at(a.timeMillis)),element("span",statusName(a.status),"world-status "+(/REJECTED|FAILED/.test(a.status)?"world-status-failed":"")));
      const title=element("h3");title.append(button(personName(a.person),()=>api.onPerson?.(a.person)),element("span"," · "+actionName(a.action)));
      row.append(heading,title,element("p",a.reason||"尚无记录的决定依据"));
      const links=element("div",null,"world-activity-links");
      const linkedCompany=activityCompany(a);
      if(linkedCompany)links.append(button(companyName(linkedCompany),()=>choose(linkedCompany,"world-companies")));
      if(a.sourceDoc){
        if((data.news||[]).some(n=>n.docId===a.sourceDoc))links.append(button("查看消息原文",()=>{selectedSource=a.sourceDoc;renderNews();$("world-news").scrollIntoView({behavior:"smooth",block:"start"});}));
        else {
          const research=data.activities.find(r=>r.action==="RESEARCH"&&r.details?.docId===a.sourceDoc);
          if(research&&(data.news||[]).some(n=>n.docId===research.sourceDoc))links.append(button("查看研究引用原文",()=>{selectedSource=research.sourceDoc;renderNews();$("world-news").scrollIntoView({behavior:"smooth",block:"start"});}));
          else links.append(element("span","依据：已记录的研究或来源资料（编号见决定详情）","world-note"));
        }
      }
      if(a.details&&Object.keys(a.details).length){const extra=element("details");extra.append(element("summary","查看决定详情"));const dl=element("dl",null,"world-decision-details");
        const labels={quantity:"数量",symbol:"股票",price:"价格",limitPrice:"限价",side:"方向",target:"目标持仓",targetQuantity:"目标持仓",accountId:"账户",docId:"资料",fairValue:"判断估值",cash:"现金",amount:"金额",action:"动作",result:"执行结果",reason:"原因",company:"公司"};
        for(const [key,value] of Object.entries(a.details)){dl.append(element("dt",labels[key]||key),element("dd",typeof value==="object"?JSON.stringify(value):value));}extra.append(dl);row.append(extra);}
      row.append(links);feed.append(row);
    }
    renderNews();
  }
  function renderNews() {
    const all=(view.connected?.news||[]).slice().sort((a,b)=>Number(b.publishedAt)-Number(a.publishedAt));
    const rows=all.slice(0,20),requested=all.find(n=>n.docId===selectedSource);
    if(requested&&!rows.includes(requested))rows.unshift(requested);
    const box=$("world-news");box.replaceChildren();
    if(!rows.length)box.append(element("p","尚无公开消息。经营记录不会自动成为所有人物已经知道的信息。","world-empty"));
    for(const item of rows){const card=element("details",null,"world-news-card");card.open=item.docId===selectedSource;
      const summary=element("summary");summary.append(element("span",item.title),element("small",personName(item.publisher)+" · "+at(item.publishedAt)));
      card.append(summary,element("p",item.body,"world-news-body"));box.append(card);}
  }
  function renderMarket() {
    const c=company(),d=detail();$("world-market-name").textContent=c.name;$("world-market-business").textContent=c.business;
    const change=c.lastPrice==null?null:(Number(c.lastPrice)/Number(c.referencePrice)-1)*100;
    $("world-quote").replaceChildren(metric("最近成交价",money(c.lastPrice),"开局参考价 "+money(c.referencePrice)),metric("相对开局参考",change==null?"—":(change>=0?"+":"")+change.toFixed(2)+"%","不代表实际投资收益"),metric("累计成交量",c.volume+" 股",c.trades+" 笔成交"));
    chart("world-price-chart",d?.days||[],"lastPrice",true);
    $("world-price-note").textContent=d?"当前世界实际记录。圆点是有实际成交的日子；虚线延续上次成交价，不代表当日成交。第一笔成交前不画价格。":"当前运行尚无行情记录；不会使用其他世界的季度数据补图。";
    table("world-trade-days",["实验日","收盘成交价","成交笔数","成交量","成交额"],(d?.days||[]).filter(r=>r.trades>0).map(r=>["第"+r.day+"日",money(r.close),r.trades,r.volume+" 股",money(r.turnover)]));
    $("world-orders-panel").hidden=!view.connected?.enabled;
    const orders=(view.connected?.orders||[]).filter(o=>o.symbol===c.symbol);
    table("world-orders",["账户","方向","限价","委托数量","已成交","状态"],orders.slice(-40).reverse().map(o=>[personName(o.accountId),o.side==="BUY"?"买入":o.side==="SELL"?"卖出":o.side,o.price==null?"市价":money(o.price),o.quantity+" 股",o.filled+" 股",statusName(o.status)]));
    $("world-orders-note").textContent=orders.length?"此股票 "+orders.length+" 条委托记录，显示最近40条。":"这只股票尚无委托。可在世界动态中查看人物的判断与等待原因。";
  }
  function section(title,values,period=false) {
    const box=element("section",null,"world-statement-section");box.append(element("h3",title));
    const dl=element("dl");for(const [key,value] of Object.entries(values||{})){const label=accountLabel(key);dl.append(element("dt",period?label.replace(/（累计）$/,"（本期）"):label),element("dd",money(value)));}box.append(dl);return box;
  }
  function renderCompany() {
    if(view.connected?.enabled){const select=$("world-report-period"),periods=detail()?.periods||[];
      for(const option of select.children)option.disabled=option.value!=="current"&&!periods.some(p=>String(p.period)===option.value);
      if(select.value!=="current"&&!periods.some(p=>String(p.period)===select.value))select.value="current";
    } else for(const option of $("world-report-period").children)option.disabled=false;
    const c=company(),d=detail(),period=$("world-report-period").value;
    const item=period==="current"?null:d?.periods.find(p=>String(p.period)===period);
    const report=item?.statement||(period==="current"?c.statement:null);
    $("world-report-name").textContent=c.name;$("world-report-description").textContent=c.business+" · 机制原型："+c.prototype;
    $("world-report-cut").textContent=item?`第${item.period}期 · 第${item.day}日末；利润表为本期发生额，资产负债表为期末余额。`:"当前已确认状态 · 利润表为从开局至今的累计额。";
    if((view.behavior?.enabled||view.connected?.enabled)&&!item&&c.id.startsWith("mfg-"))$("world-report-cut").textContent+=" 本模型固定费用按30日关期入账，期中利润尚未包含本期固定费用；客户报价和成本规模仍为未校准实验值。";
    for(const id of ["world-balance","world-income","world-report-metrics","world-cash-chart","world-boundaries"])$(id).replaceChildren();
    $("world-cash-summary").textContent="";
    if(!report){$("world-balance").append(element("p","这份期末报表尚不可用。"));return;}
    const profit=item?report.profitPeriod:report.profit;
    $("world-report-metrics").append(metric("总资产",money(report.totalAssets)),metric("总负债",money(report.totalLiabilities)),metric("净资产",money(report.totalEquity)),metric(item?"本期利润":"累计利润",money(profit)));
    $("world-balance").append(section("资产",report.assets));
    if(Object.keys(report.contra||{}).length)$("world-balance").append(section("减：资产减值准备",report.contra));
    $("world-balance").append(section("负债",report.liabilities),section("所有者权益",report.equity));
    $("world-income").append(section("收入",item?report.incomePeriod:report.income,!!item),section("费用",item?report.expensePeriod:report.expense,!!item),section(item?"本期结果":"累计结果",{"利润":profit}));
    $("world-cash-summary").textContent=d?"开局现金 / 准备金 "+money(d.openingCash)+" → 当前 "+money(c.cash)+"，净变动 "+money(d.cashChange)+"。图中为实际记录的现金观察点；现金变化不等于利润。":"当前现金 / 准备金 "+money(c.cash);
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
      if(view.connected?.enabled){const last=view.connected.peopleActivity?.[p.id];const activity=element("div",null,"world-person-activity");
        activity.append(element("small",last?at(last.timeMillis):"当前行为"),element("strong",last?actionName(last.action):"尚无持续行为记录"),element("p",last?.reason||"此人物的后续活动以实际记录为准。"));card.append(activity);}
      const id=element("details");id.append(element("summary","查看身份编号"),element("code",p.id));card.append(id);
      if(!p.system)card.append(button("进入人物视角",()=>api.onPerson?.(p.id)));grid.append(card);
    }
  }
  function paint() {
    if(!view?.available)return;
    for(const id of ["world-market-select","world-company-select"]){const select=$(id);select.replaceChildren();for(const c of view.companies){const o=element("option",c.name);o.value=c.id;select.append(o);}select.value=selected;}
    renderOverview();renderMarket();renderCompany();renderPeople();
  }
  const api={onPage:null,onPerson:null,onAdvance:null,
    render(value){
      if(view?.runId&&value?.runId&&view.runId!==value.runId){generation++;stepping=false;stopRunning();selectedSource="";}
      view=value?.available?value:null;document.querySelectorAll("[data-world-nav]").forEach(n=>n.hidden=!view);
      $("world-home").hidden=!view;
      if(!view){api.clear();return;}
      for(const page of ["market","company","org","people"])document.querySelector(`[data-page="${page}"]`).hidden=true;
      if(!view.companies.some(c=>c.id===selected))selected=view.connected?.enabled&&view.companies.some(c=>c.trades>0)?view.companies.find(c=>c.trades>0).id:view.behavior?.enabled&&view.companies.some(c=>c.id==="mfg-tech")?"mfg-tech":view.companies[0].id;
      if(!initialized){$("overview-audit").open=false;initialized=true;}
      $("context-time").textContent="第 "+Math.floor(Number(view.timeMillis)/86400000)+" 个实验日 · "+view.companies.length+" 家公司";
      paint();
    },
    clear(){
      generation++;running=false;stepping=false;clearTimeout(runTimer);runTimer=null;
      view=null;selected="";initialized=false;selectedSource="";
      $("world-connected").hidden=true;$("world-orders-panel").hidden=true;
      for(const id of ["world-connections","world-activities","world-news","world-orders","world-feed-company"])$(id).replaceChildren();
      for(const id of ["world-connected-status","world-orders-note","world-connected-toggle"])$(id).textContent="";
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
  $("world-feed-company").addEventListener("change",()=>view&&renderConnected());
  $("world-connected-toggle").addEventListener("click",()=>{if(running){stopRunning(stepping?"正在完成已提交的这一天，随后暂停。":"已暂停。");return;}if(stepping||!view?.connected?.enabled)return;running=true;void advanceDay();});
  $("world-connected-day").addEventListener("click",()=>{if(!running)void advanceDay();});
  $("world-open-controls").addEventListener("click",()=>{$("overview-audit").open=true;$("advance-form").scrollIntoView({behavior:"smooth",block:"center"});});
  $("world-behavior-day").addEventListener("click",()=>{
    if(!view?.behavior?.enabled)return;
    $("advance-target").value=String(BigInt(view.timeMillis)+86400000n);
    $("advance-form").requestSubmit();
  });
  return api;
})();
