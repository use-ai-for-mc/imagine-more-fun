package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioSessionLifecycleTest {
  @Test
  void serverEndedStopsMonitorAndClosesHelper() {
    Fixture fixture = new Fixture();

    fixture.lifecycle.serverEnded();

    fixture.assertReleased();
  }

  @Test
  void helperChannelDisconnectStopsMonitorAndClosesHelper() {
    Fixture fixture = new Fixture();

    fixture.lifecycle.helperDisconnected();

    fixture.assertReleased();
  }

  @Test
  void monitorTimeoutRetriesWithBackoffThenClosesHelper() {
    Fixture fixture = new Fixture();
    TimeoutException timeout = new TimeoutException("monitor eval timed out");

    AudioSessionLifecycle.MonitorFailureDecision first = fixture.lifecycle.monitorFailed(timeout);
    AudioSessionLifecycle.MonitorFailureDecision second = fixture.lifecycle.monitorFailed(timeout);
    AudioSessionLifecycle.MonitorFailureDecision third = fixture.lifecycle.monitorFailed(timeout);

    assertTrue(first.retry());
    assertEquals(3_000, first.delayMs());
    assertTrue(second.retry());
    assertEquals(6_000, second.delayMs());
    assertFalse(third.retry());
    assertEquals(3, third.attempt());
    fixture.assertReleased();
  }

  @Test
  void repeatedStartAndStopNeverStacksHelpers() {
    AudioSessionLifecycle lifecycle = new AudioSessionLifecycle();
    AtomicInteger closes = new AtomicInteger();

    for (int i = 0; i < 8; i++) {
      lifecycle.start("session-" + i, closes::incrementAndGet);
      lifecycle.setMonitorTask(new FakeScheduledFuture());
      lifecycle.stop("normal-end");
    }

    assertEquals(8, closes.get());
    assertFalse(lifecycle.isActive());
  }

  @Test
  void leavingServerReleasesSessionResources() {
    Fixture fixture = new Fixture();

    fixture.lifecycle.leaveServer();

    fixture.assertReleased();
  }

  @Test
  void minecraftShutdownReleasesSessionResources() {
    Fixture fixture = new Fixture();

    fixture.lifecycle.minecraftStopping();

    fixture.assertReleased();
  }

  private static final class Fixture {
    private final AudioSessionLifecycle lifecycle = new AudioSessionLifecycle();
    private final AtomicInteger helperCloses = new AtomicInteger();
    private final FakeScheduledFuture monitor = new FakeScheduledFuture();
    private final FakeScheduledFuture relatedTask = new FakeScheduledFuture();

    private Fixture() {
      lifecycle.start("test-session", helperCloses::incrementAndGet);
      lifecycle.setMonitorTask(monitor);
      lifecycle.trackTask(relatedTask);
    }

    private void assertReleased() {
      assertEquals(1, helperCloses.get());
      assertTrue(monitor.isCancelled());
      assertTrue(relatedTask.isCancelled());
      assertFalse(lifecycle.isActive());
    }
  }

  private static final class FakeScheduledFuture implements ScheduledFuture<Object> {
    private boolean cancelled;

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled = true;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return cancelled;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  }
}
