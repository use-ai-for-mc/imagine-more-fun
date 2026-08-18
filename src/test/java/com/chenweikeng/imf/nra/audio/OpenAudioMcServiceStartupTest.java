package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class OpenAudioMcServiceStartupTest {
  @Test
  void slowHelperStartupDoesNotHoldTheServiceMonitor() throws NoSuchMethodException {
    int modifiers =
        OpenAudioMcService.class.getDeclaredMethod("connect", String.class).getModifiers();

    assertFalse(
        Modifier.isSynchronized(modifiers),
        "connect must not hold the service monitor while waiting for WKWebView readiness");
  }

  @Test
  void serviceStateRemainsAvailableWhileHelperWaitsForReady(@TempDir Path tempDir)
      throws Exception {
    CountDownLatch startupEntered = new CountDownLatch(1);
    CountDownLatch releaseStartup = new CountDownLatch(1);
    WebViewBridge slowBridge =
        new WebViewBridge() {
          @Override
          public boolean start() {
            startupEntered.countDown();
            try {
              assertTrue(releaseStartup.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
            return true;
          }

          @Override
          public synchronized void stop() {}
        };
    OpenAudioMcService service =
        new OpenAudioMcService(
            new AudioVolumeStore(
                tempDir.resolve("audio-state.json"), LoggerFactory.getLogger("audio-start-test")),
            () -> slowBridge);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<?> startup =
        workers.submit(() -> service.connect("https://session.openaudiomc.net#LOCK-TEST"));

    try {
      assertTrue(startupEntered.await(2, TimeUnit.SECONDS));
      Future<Boolean> stateRead =
          workers.submit(
              () -> {
                synchronized (service) {
                  return service.shouldManageServerAudioEvents();
                }
              });
      stateRead.get(1, TimeUnit.SECONDS);
    } finally {
      releaseStartup.countDown();
      startup.get(2, TimeUnit.SECONDS);
      workers.shutdownNow();
    }
  }

  @Test
  void queuedDuplicateOfferCannotLaunchASecondColdHelper() {
    assertTrue(
        OpenAudioMcService.shouldIgnoreStartupOffer(
            false,
            "https://session.openaudiomc.net#B",
            "https://session.openaudiomc.net#A",
            1000,
            2000));
  }

  @Test
  void explicitRecoveryRequestMayAcceptTheNextOffer() {
    assertFalse(
        OpenAudioMcService.shouldIgnoreStartupOffer(
            true,
            "https://session.openaudiomc.net#A",
            "https://session.openaudiomc.net#A",
            1000,
            2000));
  }

  @Test
  void offerAfterCooldownIsAccepted() {
    assertFalse(
        OpenAudioMcService.shouldIgnoreStartupOffer(
            false,
            "https://session.openaudiomc.net#B",
            "https://session.openaudiomc.net#A",
            1000,
            1000 + OpenAudioMcService.DUPLICATE_STARTUP_OFFER_WINDOW_MS));
  }

  @Test
  void runtimeConnectionIntentSurvivesServerLeaveUntilExplicitAudioDisconnect(
      @TempDir Path tempDir) {
    OpenAudioMcService service = serviceWithoutHelper(tempDir);
    try {
      assertFalse(service.shouldConnectOnJoin(false));

      service.autoConnectOnJoin();
      service.onLeaveServer();

      assertTrue(service.shouldConnectOnJoin(false));

      service.disconnectViaCommand();

      assertFalse(service.shouldConnectOnJoin(false));
    } finally {
      service.dispose();
    }
  }

  @Test
  void serverLeaveInvalidatesColdHelperStartupWithoutErasingIntent(@TempDir Path tempDir)
      throws Exception {
    CountDownLatch startupEntered = new CountDownLatch(1);
    CountDownLatch releaseStartup = new CountDownLatch(1);
    CountDownLatch stopped = new CountDownLatch(1);
    WebViewBridge slowBridge =
        new WebViewBridge() {
          @Override
          public boolean start() {
            startupEntered.countDown();
            try {
              return releaseStartup.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
          }

          @Override
          public synchronized void stop() {
            stopped.countDown();
          }
        };
    OpenAudioMcService service =
        new OpenAudioMcService(
            new AudioVolumeStore(
                tempDir.resolve("audio-state.json"), LoggerFactory.getLogger("audio-leave-test")),
            () -> slowBridge);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    service.autoConnectOnJoin();
    Future<?> startup =
        worker.submit(() -> service.connect("https://session.openaudiomc.net#LEAVE-TEST"));

    try {
      assertTrue(startupEntered.await(2, TimeUnit.SECONDS));

      service.onLeaveServer();
      releaseStartup.countDown();
      startup.get(2, TimeUnit.SECONDS);

      assertTrue(stopped.await(1, TimeUnit.SECONDS));
      assertFalse(service.isActive());
      assertTrue(service.shouldConnectOnJoin(false));
    } finally {
      releaseStartup.countDown();
      worker.shutdownNow();
      service.dispose();
    }
  }

  private static OpenAudioMcService serviceWithoutHelper(Path tempDir) {
    return new OpenAudioMcService(
        new AudioVolumeStore(
            tempDir.resolve("audio-state.json"), LoggerFactory.getLogger("audio-intent-test")),
        WebViewBridge::new);
  }
}
