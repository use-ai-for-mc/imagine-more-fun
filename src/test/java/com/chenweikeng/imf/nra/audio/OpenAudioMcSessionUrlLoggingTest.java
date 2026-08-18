package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenAudioMcSessionUrlLoggingTest {
  private static final String PATH_SECRET = "path-secret-value";
  private static final String QUERY_SECRET = "query-secret-value";
  private static final String FRAGMENT_SECRET = "fragment-secret-value";

  @Test
  void sessionUrlDescriptionContainsOnlyFixedHostAndShape() {
    String sensitiveUrl =
        "https://session.openaudiomc.net/"
            + PATH_SECRET
            + "?token="
            + QUERY_SECRET
            + "#"
            + FRAGMENT_SECRET;

    String description = OpenAudioMcService.describeSessionUrlForLog(sensitiveUrl);

    assertEquals("host=session.openaudiomc.net, shape=path+query+fragment", description);
    assertContainsNoSecrets(description);
  }

  @Test
  void unexpectedOrMalformedUrlsNeverEchoTheirInput() {
    String unexpected =
        OpenAudioMcService.describeSessionUrlForLog(
            "https://" + PATH_SECRET + ".invalid/path?token=" + QUERY_SECRET);
    String malformed = OpenAudioMcService.describeSessionUrlForLog("not a URL " + FRAGMENT_SECRET);

    assertEquals("host=unexpected, shape=path+query", unexpected);
    assertEquals("host=invalid, shape=unparseable", malformed);
    assertContainsNoSecrets(unexpected);
    assertContainsNoSecrets(malformed);
  }

  @Test
  void helperDiagnosticsRedactEmbeddedUrlsBeforeLogging() {
    String diagnostic =
        "Load failed at https://session.openaudiomc.net/path?token="
            + QUERY_SECRET
            + "#"
            + FRAGMENT_SECRET
            + " and https://media.invalid/clip?signature="
            + PATH_SECRET;

    String sanitized = OpenAudioMcService.sanitizeLogMessage(diagnostic);

    assertTrue(sanitized.contains("[url redacted]"));
    assertFalse(sanitized.contains("https://"));
    assertContainsNoSecrets(sanitized);
  }

  private static void assertContainsNoSecrets(String value) {
    assertFalse(value.contains(PATH_SECRET));
    assertFalse(value.contains(QUERY_SECRET));
    assertFalse(value.contains(FRAGMENT_SECRET));
  }
}
