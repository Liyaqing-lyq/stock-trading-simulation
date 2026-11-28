"""校验个人市价决定的原任务、交易所拒绝及未成交余量。"""
import json
import unittest

from audit_ten_company_market import PERSONAL_MARKET, PERSONAL_ORDERS, attach_market_fill, market_request


class PersonalMarketAttributionTest(unittest.TestCase):
    def setUp(self):
        self.request = dict(commandId="command", actorOwnerId="omar", accountId="HOUSE-OMAR",
                            symbol="CUFE-A", side="BUY", type="MARKET", price=None, quantity=5)
        self.decision = dict(actionId="immediate", actor="omar", taskId="task", dueMillis=1000)
        self.task = dict(taskId="task", dueTimeMillis=1000, kind="EXCHANGE_ORDER_V1",
                         payload=json.dumps(self.request))
        self.receipt = dict(taskId="task", commandId="command", timeMillis=1000, accepted=True,
                            orderId=3, reason="CANCELLED", tradeIds=[1,2])
        self.order = dict(taskId="task", type="MARKET", accountId="HOUSE-OMAR", symbol="CUFE-A",
                          side="BUY", quantity=5, filled=3, status="CANCELLED")

    def test_market_decision_requires_its_own_scheduled_order(self):
        self.assertIn(PERSONAL_MARKET, PERSONAL_ORDERS)
        self.assertEqual(self.request, market_request(self.decision, [self.task]))
        for bad in ([], [self.task, self.task], [dict(self.task, taskId="another-task")],
                    [dict(self.task, dueTimeMillis=1001)], [dict(self.task, kind="EXCHANGE_CANCEL_V1")],
                    [dict(self.task, payload=json.dumps(dict(self.request, actorOwnerId="nina")))]):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                market_request(self.decision, bad)

    def test_rejected_order_has_no_execution_or_cancelled_lot(self):
        for reason in ("SESSION_CLOSED", "NO_LIQUIDITY"):
            receipt = dict(self.receipt, accepted=False, orderId=None, reason=reason, tradeIds=[])
            original = dict(receipt)
            attach_market_fill(receipt, dict(self.decision, marketRequest=self.request), {})
            self.assertEqual(original, receipt)

    def test_fills_and_cancelled_remainder_come_from_the_native_order(self):
        for qty, status, cancelled in ((3, "CANCELLED", 2), (5, "FILLED", 0)):
            receipt = dict(self.receipt)
            attach_market_fill(receipt, dict(self.decision, marketRequest=self.request),
                               {3:dict(self.order, filled=qty, status=status)})
            self.assertEqual((qty, cancelled), (receipt["filledQuantity"], receipt["immediateCancelledQuantity"]))
            self.assertEqual([1, 2], receipt["tradeIds"])
        for bad in (dict(self.order, taskId="other"), dict(self.order, accountId="INV"),
                    dict(self.order, type="LIMIT"), dict(self.order, quantity=8), dict(self.order, status="OPEN")):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                attach_market_fill(dict(self.receipt), dict(self.decision, marketRequest=self.request), {3:bad})
        with self.assertRaises(ValueError):
            attach_market_fill(dict(self.receipt, commandId="another-command"),
                               dict(self.decision, marketRequest=self.request), {3:self.order})


if __name__ == "__main__":
    unittest.main()
