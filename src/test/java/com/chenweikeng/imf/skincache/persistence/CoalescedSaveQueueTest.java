package com.chenweikeng.imf.skincache.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoalescedSaveQueueTest {

  @Test
  void burstOfRequestsSchedulesAndRunsOneSave() {
    FakeScheduler scheduler = new FakeScheduler();
    AtomicInteger saves = new AtomicInteger();
    CoalescedSaveQueue queue =
        new CoalescedSaveQueue(scheduler, 30, TimeUnit.SECONDS, () -> saves.incrementAndGet() > 0);

    for (int i = 0; i < 10_000; i++) {
      queue.requestSave();
    }

    assertEquals(1, scheduler.size());
    assertTrue(queue.hasPendingSave());

    scheduler.runNext();

    assertEquals(1, saves.get());
    assertEquals(0, scheduler.size());
    assertFalse(queue.hasPendingSave());
  }

  @Test
  void mutationDuringSaveSchedulesExactlyOneFollowUp() {
    FakeScheduler scheduler = new FakeScheduler();
    AtomicInteger saves = new AtomicInteger();
    CoalescedSaveQueue[] queueRef = new CoalescedSaveQueue[1];
    CoalescedSaveQueue queue =
        new CoalescedSaveQueue(
            scheduler,
            30,
            TimeUnit.SECONDS,
            () -> {
              if (saves.incrementAndGet() == 1) {
                queueRef[0].requestSave();
                queueRef[0].requestSave();
              }
              return true;
            });
    queueRef[0] = queue;

    queue.requestSave();
    scheduler.runNext();

    assertEquals(1, saves.get());
    assertEquals(1, scheduler.size());

    scheduler.runNext();

    assertEquals(2, saves.get());
    assertEquals(0, scheduler.size());
    assertFalse(queue.hasPendingSave());
  }

  @Test
  void failedSaveRemainsDirtyAndRetriesLater() {
    FakeScheduler scheduler = new FakeScheduler();
    AtomicInteger attempts = new AtomicInteger();
    CoalescedSaveQueue queue =
        new CoalescedSaveQueue(
            scheduler, 30, TimeUnit.SECONDS, () -> attempts.incrementAndGet() >= 2);

    queue.requestSave();
    scheduler.runNext();

    assertEquals(1, attempts.get());
    assertEquals(1, scheduler.size());
    assertTrue(queue.hasPendingSave());

    scheduler.runNext();

    assertEquals(2, attempts.get());
    assertFalse(queue.hasPendingSave());
  }

  @Test
  void flushNowPersistsWithoutWaitingForScheduledTask() {
    FakeScheduler scheduler = new FakeScheduler();
    AtomicInteger saves = new AtomicInteger();
    CoalescedSaveQueue queue =
        new CoalescedSaveQueue(scheduler, 30, TimeUnit.SECONDS, () -> saves.incrementAndGet() > 0);

    queue.requestSave();
    queue.flushNow();

    assertEquals(1, saves.get());
    scheduler.runNext();
    assertEquals(1, saves.get());
    assertFalse(queue.hasPendingSave());
  }

  private static final class FakeScheduler implements CoalescedSaveQueue.Scheduler {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void schedule(Runnable task, long delay, TimeUnit unit) {
      tasks.add(task);
    }

    private int size() {
      return tasks.size();
    }

    private void runNext() {
      Runnable task = tasks.remove();
      task.run();
    }
  }
}
