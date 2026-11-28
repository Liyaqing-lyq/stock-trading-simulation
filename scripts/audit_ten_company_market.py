"""封存的普通十公司日志：实缴对账、交易时间、真实报价与基金经营回报。--after 是不计入流量的已确认起点。"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from audit_ten_company_run import checked_frames, check_policies, closing_book, decode, DAY, patch_field
from audit_ten_company_settlements import audit as settled

STAGES = {f'ROLE_TRADING_{s}_V1':s for s in ('RESEARCH','TARGET','REVIEW','CONFIRM','CHILD','CHILD_CANCEL','HALT','CANCEL')}
FIELDS = ('timeMillis','exchange','trading','roles','pending')
PERSONAL_MARKET = 'personal.order.market'
PERSONAL_ORDERS = {'personal.order.limit', PERSONAL_MARKET, 'personal.order.cancel'}
DESK = {'RESEARCHER','PORTFOLIO_MANAGER','RISK_OFFICER','EXECUTION_TRADER'}
MINUTE = 60_000

def open_until(t):
    days, intra = divmod(t,DAY)
    weeks, weekday = divmod(days,7)
    sessions = (weeks*5+min(weekday,5))*240*MINUTE
    if weekday < 5:
        for start in (570,780):
            sessions += max(0,min(intra-start*MINUTE,120*MINUTE))
    return sessions

def phase(t,closed):
    if closed: return 'PERMANENTLY_CLOSED'
    if t//DAY%7>=5: return 'WEEKEND'
    now=t%DAY//MINUTE
    return 'PREOPEN' if now<570 else 'OPEN' if now<690 else 'LUNCH_BREAK' if now<780 else 'OPEN' if now<900 else 'AFTER_CLOSE'

def compact_book(exchange, symbols):
    return {k:v for k,v in closing_book(exchange['orders'],symbols).items()
            if v['bestBid'] is not None or v['bestAsk'] is not None}

def market_request(decision, pending_tasks):
    matching = [task for task in pending_tasks if task['taskId'] == decision['taskId']]
    if len(matching) != 1:
        raise ValueError('个人市价决定缺少唯一的原委托任务')
    task = matching[0]
    request = decode(task['payload'])
    if (task['kind'], task['dueTimeMillis'], request['type'], request['actorOwnerId']) != (
            'EXCHANGE_ORDER_V1', decision['dueMillis'], 'MARKET', decision['actor']):
        raise ValueError('个人市价决定与原委托任务不符')
    return request


def attach_market_fill(receipt, decision, orders):
    request = decision['marketRequest']
    if receipt['commandId'] != request['commandId'] or receipt['timeMillis'] != decision['dueMillis']:
        raise ValueError('市价委托回报与原任务不符')
    if not receipt['accepted']:
        return
    actual = orders[receipt['orderId']]
    if (actual['taskId'], actual['type'], actual['accountId'], actual['symbol'], actual['side'], actual['quantity']) != (
            receipt['taskId'], 'MARKET', request['accountId'], request['symbol'], request['side'], request['quantity']):
        raise ValueError('成交或余撤与本人原市价单不符')
    filled, quantity = actual['filled'], actual['quantity']
    if not 0 <= filled <= quantity or actual['status'] != ('FILLED' if filled == quantity else 'CANCELLED'):
        raise ValueError('市价单办结后仍有未登记的余量')
    receipt['filledQuantity'] = filled
    receipt['immediateCancelledQuantity'] = quantity - filled


def report(path, after, settlement=None):
    if settlement is None: settlement=settled(path)
    digest=hashlib.sha256()
    state={}; scheduled={}; stages=[]; completed=set(); orders=[]; quotes={}
    actual_reads=Counter(); opening_offices=[]; first_quote=None; closing_quotes={}; open_ms=0; start=None; saw_open=False
    refs={}; decision_owners={}; personal={}
    for f in checked_frames(path,digest):
        pos=f['position']; events=f['events']
        patch=decode(next(e['payload'] for e in events if e['type']=='STATE_DELTA_V1'))['patch']
        old_time=state.get('timeMillis',0)
        closed=state['exchange']['closed'] if state else False
        if pos==1:
            manifest=decode(f['commandContent'])['manifest']
            check_policies(f['runId'],manifest['dataVersions'])
            if manifest['dataVersions'].get('exchange-sessions')!='weekday-hy1-v1': raise ValueError('unrecognized calendar')
            state={k:patch['value'][k] for k in FIELDS}
            registry=decode(next(e['payload'] for e in events if e['type']=='OPENING_REGISTRY_V1'))
            symbols=[s['symbol'] for s in registry['securities']]
            owner={o['ownerId']:o['kind'] for o in registry['owners']}
            accounts={k:(v['ownerId'],owner[v['ownerId']]) for k,v in patch['value']['accounts'].items()}
            opening_offices=[a for a in state['roles']['access']['appointments'] if a['role'] in DESK]
            refs=patch['value']['decisions']['actionRefs']
        else:
            for k in FIELDS:
                if k in patch['changes']: state[k]=patch_field(state[k],patch['changes'][k])
            dec=patch['changes'].get('decisions',{})
            if dec.get('kind')=='replace': refs=dec['value']['actionRefs']; touched=refs
            else:
                ref_patch=dec.get('changes',{}).get('actionRefs')
                if ref_patch:
                    refs=patch_field(refs,ref_patch)
                    touched=ref_patch['changes'] if ref_patch['kind']=='object' else refs
                else:touched=()
            for action_id in touched:
                ref=refs[action_id]
                if ref['tool'] in PERSONAL_ORDERS and ref['taskId'] not in personal:
                    row = dict(ref, scheduledAt=pos, actor=decision_owners[action_id])
                    if ref['tool'] == PERSONAL_MARKET:
                        row['marketRequest'] = market_request(row, state['pending'])
                    personal[ref['taskId']] = row
        t=state['timeMillis']
        if pos==max(after,1): start={'position':pos,'timeMillis':t}
        if start and pos>start['position'] and not closed: open_ms+=open_until(t)-open_until(old_time)
        if pos>=max(after,1):
            saw_open |= phase(t,state['exchange']['closed'])=='OPEN'
            if pos==max(after,1) or 'exchange' in patch.get('changes',{}):
                closing_quotes=compact_book(state['exchange'],symbols)
                if closing_quotes and first_quote is None:first_quote={'position':pos,'timeMillis':t}
                for symbol,book in closing_quotes.items():
                    q=quotes.setdefault(symbol,{'bid':False,'ask':False,'bothAtSameCut':False})
                    bid,ask=book['bestBid'] is not None,book['bestAsk'] is not None
                    q['bid']|=bid;q['ask']|=ask;q['bothAtSameCut']|=bid and ask
        task=None
        for e in events:
            k=e['type']
            if k=='DECISION_OPENED_V1':
                for d in decode(e['payload']):decision_owners[d['actionId']]=d['personPartyId']
            elif k=='TASK_COMPLETED_V1': task=decode(e['payload'])
            elif k=='DECISION_ACTION_SCHEDULED_V1':
                s=decode(e['payload'])
                if s.get('kind') in STAGES: scheduled[s['commandId']]=(pos,s)
            elif k=='ROLE_TRADING_REPORT_V1':
                r=decode(e['payload'])
                if r['kind'] not in STAGES:continue
                if not task or (r['position'],r['kind'],r['personPartyId'],r['clientCommandId']) != (
                        pos,task['kind'],task['personPartyId'],task['clientCommandId']):raise ValueError('role task/receipt mismatch')
                source=scheduled.get(task['commandId'])
                if source and source[1]!=task:raise ValueError('scheduled payload differs from completion')
                completed.add(task['commandId'])
                if pos>after:
                    stages.append({'stage':STAGES[r['kind']],'position':pos,'actor':r['personPartyId'],'subject':r['subjectId'],
                       'accepted':r['accepted'],'reason':r['reason'],'taskId':r['taskId'],
                       'modelActionId':task['clientCommandId'] if source else None,'scheduledAt':source[0] if source else None,
                       'terms':{key:value for key,value in decode(task['body']).items() if key in {
                       'sourceResearchId','researchId','targetId','expectedVersion','absoluteTargetQuantity','approved',
                       'maxTotalQuantity','maxChildQuantity','priceLimit','riskRevision','parentId','parentVersion','childId','quantity','limitPrice'}}})
            elif pos>after and k=='EXCHANGE_ORDER_REPORT_V1':orders.append(decode(e['payload']))
            elif pos>after and k=='INFORMATION_OBSERVATION_V1':
                r=decode(e['payload'])
                if any(a['personPartyId']==r['personPartyId'] and a['role'] in DESK and a['startsAt']<=t<a['endsAt']
                       and (a['revokedAt'] is None or t<a['revokedAt']) for a in state['roles']['access']['appointments']):
                    actual_reads[r['personPartyId']]+=1
    if start is None or after<0 or digest.hexdigest()!=settlement['journalSHA256']: raise ValueError('invalid cutoff or changed archive')
    reports={r['taskId']:r for r in orders}
    for s in stages:
        if s['stage']=='CHILD':
            s['orderReceipt']=reports.get(s['taskId'])
            if s['accepted'] and not (s['orderReceipt'] and s['orderReceipt']['accepted']): raise ValueError('missing exchange admission')
    frames=[f for f in settlement['framesWithTrades'] if f['position']>after]
    pairs={}; actors={}; totals=Counter(); trade_orders={}
    for f in frames:
        for tr in f['trades']:
            buy,sell=accounts[tr['buyAccountId']][0],accounts[tr['sellAccountId']][0]
            amount=dict(fills=1,shares=tr['quantity'],turnover=tr['amount'])
            pair=tuple(sorted([buy,sell]))
            pairs.setdefault(pair,Counter()).update(amount)
            for x,y in sorted({(buy,sell),(sell,buy)}): actors.setdefault(x,{}).setdefault(y,Counter()).update(amount)
            totals.update(amount)
            for side in ('buy','sell'):trade_orders.setdefault(tr[side+'OrderId'],[]).append(tr['tradeId'])
    led=state['trading']['trading']
    book_orders={o['orderId']:o for o in state['exchange']['orders']}
    for order in orders:
        if order['orderId']:
            o=book_orders.get(order['orderId'])
            if o:
                owner_id,owner_kind=accounts[o['accountId']]
                order.update(ownerId=owner_id,ownerKind=owner_kind,orderSide=o['side'],symbol=o['symbol'],cumulativeOrderStatus=o['status'])
        if order['taskId'] in personal:
            decision = personal[order['taskId']]
            order['modelActionId'] = decision['actionId']
            if decision['tool'] == PERSONAL_MARKET:
                attach_market_fill(order, decision, book_orders)
    child_fills=[dict(**c,intervalTradeIds=trade_orders.get(c['orderId'],[])) for c in led['children']]
    concentration={}
    for k in ('fills','shares','turnover'):
        maximum=max((row[k] for row in pairs.values()),default=None)
        concentration[k]=None if maximum is None else {'numerator':maximum,'denominator':totals[k],
                                        'owners':[pair for pair,row in pairs.items() if row[k]==maximum]}
    return {'journalSHA256':digest.hexdigest(),'runId':manifest['runId'],'intervalStart':start,'intervalEnd':{'position':pos,'timeMillis':t},
            'phaseAtEnd':phase(t,state['exchange']['closed']),'marketOpenMillis':open_ms,'marketOpenedInWindow':saw_open or open_ms>0,
            'quotePresence':quotes,'firstObservedLiveBook':first_quote,'closingNonemptyBooks':closing_quotes,
            'openingFundOffices':opening_offices,'completedReadsByInOfficeDeskPerson':actual_reads,
            'personalModelOrderTasks':[dict(r,exchangeReceipt=reports.get(task)) for task,r in personal.items()
                                       if r['scheduledAt']>after or task in reports],
            'fundStagesInWindow':stages,'fundStageCounts':{s:dict(Counter('accepted' if row['accepted'] else 'rejected' for row in stages if row['stage']==s)) for s in STAGES.values()},
            'unexecutedModelFundSchedules':[{ 'position':p,'actionId':s['clientCommandId'],'stage':STAGES[s['kind']]} for key,(p,s) in scheduled.items() if key not in completed and p>after],
            'closingFundLedger':led,'fundChildOrderFillLinks':child_fills,'exchangeReportsInWindow':orders,'confirmedFillFrames':frames,
            'settledTotals':{k:totals[k] for k in ('fills','shares','turnover')},
            'largestOwnerPairShare':concentration,
            'sameOwnerPairAggregatedAcrossDirections':[{'owners':pair,**v} for pair,v in sorted(pairs.items())],
            'counterpartiesByOwner':actors}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('journal',type=Path);parser.add_argument('output',type=Path)
    parser.add_argument('--after',type=int,default=0)
    args=parser.parse_args()
    result=report(args.journal,args.after)
    with args.output.open('x',encoding='utf8') as out:json.dump(result,out,indent=2,ensure_ascii=False,default=str)


if __name__ == '__main__':
    main()
