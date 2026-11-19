package com.datadog.openfeature.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.datadog.openfeature.internal.ufc.ParsedSemver;
import org.junit.jupiter.api.Test;

class SemverParseCacheTest {

  @Test
  void parsesEachValueOnce() {
    final SemverParseCache cache = new SemverParseCache();
    final String version = "1.49.3-beta.11.rc-7+build.20261117.sha.0123456789abcdef";

    final ParsedSemver first = cache.parse(version);
    assertSame(first, cache.parse(version));
    // An equal string that is another instance reuses the parse too.
    assertSame(first, cache.parse(new String(version)));

    assertEquals(1, cache.parses());
    assertEquals(ParsedSemver.parse(version), first);
  }

  @Test
  void remembersInvalidVersions() {
    final SemverParseCache cache = new SemverParseCache();

    assertNull(cache.parse("not-a-version"));
    assertNull(cache.parse("not-a-version"));

    assertEquals(1, cache.parses());
  }

  @Test
  void keepsEveryDistinctValue() {
    final SemverParseCache cache = new SemverParseCache();
    final String[] versions = {"1.0.0", "2.0.0-rc.1", "bad", "3", "4.5.6+build", "1.0.0-alpha"};

    for (int round = 0; round < 3; round++) {
      for (final String version : versions) {
        assertEquals(ParsedSemver.parse(version), cache.parse(version), version);
      }
    }

    assertEquals(versions.length, cache.parses());
  }
}
