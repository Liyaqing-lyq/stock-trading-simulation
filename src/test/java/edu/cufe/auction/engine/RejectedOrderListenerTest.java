package edu.cufe.auction.engine;

import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒单通知的锁边界测试。
 *
 * <p>{@link EngineListener} 的契约是“回调在引擎锁外触发”，否则监听者里的 I/O
 * （CSV 落盘）会拖住撮合，监听者若等待其他线程读引擎还会死锁。这里用
 * “回调内起线程读引擎并等待”的方式证明：若通知仍持有引擎锁，辅助线程必然
 * 卡在锁上、回调的有限等待超时，断言随之为红。</p>
 */
@DisplayName("拒单通知在引擎锁外触发")
class RejectedOrderListenerTest {

    private static final long PROBE_WAIT_MILLIS = 3000L;

    @Test
    @DisplayName("回调中另一线程读取引擎可在有限时间内完成，说明通知不持有引擎锁")
    void rejectionNotificationRunsOutsideEngineLock() {
        EngineFixture fixture = new EngineFixture();
        fixture.account("acc-a", "10000.00");

        AtomicBoolean probeCompleted = new AtomicBoolean(false);
        AtomicBoolean completedDuringCallback = new AtomicBoolean(false);
        AtomicReference<Throwable> probeFailure = new AtomicReference<>();
        AtomicReference<Thread> probeThread = new AtomicReference<>();

        fixture.engine.addListener(new EngineListener() {
            @Override
            public void onOrderRejected(OrderRequest request, String reason) {
                Thread probe = new Thread(() -> {
                    try {
                        // 任何一次进入引擎都会先拿 lock；锁被持有则这里阻塞
                        fixture.engine.lastPrices();
                        fixture.engine.marketSnapshot(EngineFixture.AAPL, 1);
                        probeCompleted.set(true);
                    } catch (RuntimeException ex) {
                        probeFailure.set(ex);
                    }
                }, "reject-probe");
                probe.setDaemon(true);
                probeThread.set(probe);
                probe.start();
                try {
                    probe.join(PROBE_WAIT_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                // 在回调内部就记录结果：回调之外再读会因锁已释放而变成假绿
                completedDuringCallback.set(probeCompleted.get());
            }
        });

        OrderResult result;
        try {
            result = fixture.engine.submit(OrderRequest.limit(
                    "acc-a", "NO_SUCH_SYMBOL", Side.BUY, new BigDecimal("10.00"), 100));
        } finally {
            Thread probe = probeThread.get();
            if (probe != null && probe.isAlive()) {
                probe.interrupt();
                try {
                    probe.join(1000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        assertFalse(result.isAccepted());
        assertNull(probeFailure.get(), "辅助线程不应抛异常：" + probeFailure.get());
        assertTrue(completedDuringCallback.get(),
                "拒单回调期间辅助线程必须能读取引擎；未完成说明通知仍持有引擎锁");
    }

    @Test
    @DisplayName("拒单仍按原契约通知监听者，且不产生订单与成交")
    void rejectionStillReportedToListener() {
        EngineFixture fixture = new EngineFixture();
        fixture.account("acc-a", "10000.00");

        AtomicReference<OrderRequest> seenRequest = new AtomicReference<>();
        AtomicReference<String> seenReason = new AtomicReference<>();
        fixture.engine.addListener(new EngineListener() {
            @Override
            public void onOrderRejected(OrderRequest request, String reason) {
                seenRequest.set(request);
                seenReason.set(reason);
            }
        });

        OrderResult result = fixture.engine.submit(OrderRequest.limit(
                "acc-a", "NO_SUCH_SYMBOL", Side.BUY, new BigDecimal("10.00"), 100));

        assertFalse(result.isAccepted());
        assertNotNull(result.getReason());
        assertNull(result.getOrder(), "被拒绝时不应有订单对象");
        assertTrue(result.getTrades().isEmpty());
        assertEquals(0L, result.getFilledQuantity());
        assertNotNull(seenRequest.get(), "监听者应收到原始请求");
        assertEquals(result.getReason(), seenReason.get());
        assertEquals("NO_SUCH_SYMBOL", seenRequest.get().getSymbol());
    }
}
