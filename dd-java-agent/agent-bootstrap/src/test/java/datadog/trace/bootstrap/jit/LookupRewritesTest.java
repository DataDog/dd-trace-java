package datadog.trace.bootstrap.jit;

import static datadog.trace.bootstrap.jit.Outcomes.assertSameOutcome;
import static datadog.trace.bootstrap.jit.Outcomes.outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Differential tests: each lookup helper must behave exactly like the JDK call it replaces. */
class LookupRewritesTest {

  private static final String[] CHARSET_NAMES = {
    "UTF-8", "utf-8", "Utf-8", "UTF-16", "utf-16be", "UTF-16LE", "US-ASCII", "us-ascii",
    "ISO-8859-1", "iso-8859-1", "ıso-8859-1", "UTF8", "utf8", "latin1", "ASCII", "UTF-32",
    "windows-1252", "bogus", "", " UTF-8", "UTF-8 ", "UTF_8", null
  };

  private static final String[] STRINGS = {
    "", "plain", "café", "日本", "😀", "\uD800", "\uDC00x", null
  };

  private static final byte[][] BYTES = {
    {},
    "plain".getBytes(Charset.forName("US-ASCII")),
    {(byte) 0xC3, (byte) 0xA9},
    {(byte) 0xFF, (byte) 0xFE, 0x41, 0x00},
    {(byte) 0xE2, (byte) 0x82},
    {(byte) 0x80, 0x41, (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80},
    null
  };

  private static final int[][] RANGES = {{0, 0}, {0, 1}, {1, 2}, {0, 6}, {-1, 1}, {2, -1}, {5, 3}};

  private static final String[] ZONE_IDS = {
    "Z",
    "UTC",
    "GMT",
    "UT",
    "UTC+01:00",
    "UTC+1",
    "GMT-5",
    "GMT-05:30",
    "UT+3",
    "+01:00",
    "-05:30",
    "+1",
    "+25:00",
    "UTC+25:00",
    "GMT0",
    "UTC0",
    "UCT",
    "Etc/UTC",
    "Etc/GMT+5",
    "America/New_York",
    "Europe/Paris",
    "Asia/Kolkata",
    "bogus",
    "",
    "utc",
    "UTC+",
    null
  };

  @Test
  void getBytesMatchesJdk() {
    for (String s : STRINGS) {
      for (String name : CHARSET_NAMES) {
        assertSameOutcome(
            bytes(() -> s.getBytes(name)), bytes(() -> LookupRewrites.getBytes(s, name)));
      }
    }
  }

  @Test
  void newStringMatchesJdk() {
    for (byte[] b : BYTES) {
      for (String name : CHARSET_NAMES) {
        assertSameOutcome(() -> new String(b, name), () -> LookupRewrites.newString(b, name));
        for (int[] range : RANGES) {
          assertSameOutcome(
              () -> new String(b, range[0], range[1], name),
              () -> LookupRewrites.newString(b, range[0], range[1], name));
        }
      }
    }
  }

  @Test
  void forNameReturnsTheSameInstanceAsJdk() {
    for (String name : CHARSET_NAMES) {
      Object expected = outcome(() -> Charset.forName(name));
      Object actual = outcome(() -> LookupRewrites.forName(name));
      if (expected instanceof Charset) {
        assertSame(expected, actual, name);
      } else {
        assertEquals(expected, actual, name);
      }
    }
  }

  @Test
  void zoneOfMatchesJdkColdAndWarm() {
    for (int pass = 0; pass < TieredCache.PROMOTION_THRESHOLD + 3; pass++) {
      for (String id : ZONE_IDS) {
        assertSameOutcome(() -> describe(ZoneId.of(id)), () -> describe(LookupRewrites.zoneOf(id)));
      }
    }
    assertTrue(LookupRewrites.hasFixedRules("UTC+01:00"));
    assertTrue(LookupRewrites.hasFixedRules("-05:30"));
    assertFalse(LookupRewrites.hasFixedRules("GMT0")); // a provider region, not a prefix
    assertFalse(LookupRewrites.hasFixedRules("America/New_York"));
  }

  @Test
  void warmZoneIsCachedOnlyForFixedRules() {
    for (int i = 0; i <= TieredCache.PROMOTION_THRESHOLD; i++) {
      LookupRewrites.zoneOf("UTC");
      LookupRewrites.zoneOf("Europe/Paris");
    }
    ZoneId utc = LookupRewrites.zoneOf("UTC");
    assertNotNull(utc);
    assertSame(utc, LookupRewrites.zoneOf("UTC"));
  }

  /** Everything observable about a zone, so equal descriptions mean equal behavior. */
  private static String describe(ZoneId zone) {
    return zone.getClass().getName()
        + " "
        + zone.getId()
        + " "
        + zone.normalized()
        + " "
        + zone.getRules()
        + " "
        + zone.hashCode();
  }

  /** Compares byte arrays by content. */
  private static Callable<Object> bytes(Callable<byte[]> call) {
    return () -> Arrays.toString(call.call());
  }
}
