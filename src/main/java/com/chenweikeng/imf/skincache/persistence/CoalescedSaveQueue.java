package com.chenweikeng.imf.skincache.persistence;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Coalesces a burst of cache mutations into one delayed persistence operation.
 *
 * <p>A mutation that arrives while a save is running is deliberately left dirty and schedules one
 * follow-up save after the debounce interval. This prevents a hot producer from turning a large
 * whole-index JSON file into a continuous write loop.
 */
public final class CoalescedSaveQueue {

  @FunctionalInterface
  interface Scheduler {
    void schedule(Runnable task, long delay, TimeUnit unit);
  }

  private final Scheduler scheduler;
  private final long delay;
  private final TimeUnit delayUnit;
  private final BooleanSupplier saveAction;
  private final AtomicBoolean dirty = new AtomicBoolean(false);
  private final AtomicBoolean scheduled = new AtomicBoolean(false);

  public CoalescedSaveQueue(
      ScheduledExecutorService executor,
      long delay,
      TimeUnit delayUnit,
      BooleanSupplier saveAction) {
    this(
        (task, scheduledDelay, scheduledUnit) ->
            executor.schedule(task, scheduledDelay, scheduledUnit),
        delay,
        delayUnit,
        saveAction);
  }

  CoalescedSaveQueue(
      Scheduler scheduler, long delay, TimeUnit delayUnit, BooleanSupplier saveAction) {
    this.scheduler = Objects.requireNonNull(scheduler);
    this.delay = delay;
    this.delayUnit = Objects.requireNonNull(delayUnit);
    this.saveAction = Objects.requireNonNull(saveAction);
  }

  /** Mark the backing data dirty and ensure one delayed save is scheduled. */
  public void requestSave() {
    dirty.set(true);
    scheduleIfNeeded();
  }

  /** Persist immediately, including access-only metadata that was not yet scheduled. */
  public void flushNow() {
    dirty.set(false);
    runSaveAction();
  }

  boolean hasPendingSave() {
    return dirty.get() || scheduled.get();
  }

  private void scheduleIfNeeded() {
    if (!scheduled.compareAndSet(false, true)) return;

    try {
      scheduler.schedule(this::runScheduledSave, delay, delayUnit);
    } catch (RuntimeException e) {
      scheduled.set(false);
      throw e;
    }
  }

  private void runScheduledSave() {
    try {
      if (dirty.getAndSet(false)) {
        runSaveAction();
      }
    } finally {
      scheduled.set(false);
      if (dirty.get()) {
        scheduleIfNeeded();
      }
    }
  }

  private void runSaveAction() {
    boolean saved = false;
    try {
      saved = saveAction.getAsBoolean();
    } finally {
      if (!saved) {
        dirty.set(true);
      }
    }
  }
}
