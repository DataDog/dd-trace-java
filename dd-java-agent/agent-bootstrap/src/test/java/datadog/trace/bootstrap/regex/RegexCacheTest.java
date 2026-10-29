package datadog.trace.bootstrap.regex;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.MULTILINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegexCacheTest {

  @BeforeEach
  void reset() {
    RegexCache.reset();
  }

  @Test
  void firstSightingIsNotCachedSecondIsAdmitted() {
    String regex = "a+b";

    Pattern first = RegexCache.get(regex);
    assertCounters(0, 1, 0);

    Pattern second = RegexCache.get(regex);
    assertNotSame(first, second);
    assertCounters(0, 2, 1);

    Pattern third = RegexCache.get(regex);
    assertSame(second, third);
    assertSame(regex, third.pattern());
    assertEquals(0, third.flags());
    assertCounters(1, 2, 1);
  }

  @Test
  void freshCopiesAreNeverCached() {
    for (int i = 0; i < 100; i++) {
      Pattern p = RegexCache.get(new String("x+y"));
      assertEquals("x+y", p.pattern());
    }
    assertCounters(0, 100, 0);
  }

  @Test
  void equalButDistinctReferencesDoNotHitEachOther() {
    String a = new String("[0-9]+");
    String b = new String("[0-9]+");
    RegexCache.get(a);
    Pattern cached = RegexCache.get(a);
    assertCounters(0, 2, 1);

    Pattern fromB = RegexCache.get(b);
    assertNotSame(cached, fromB);
    assertSame(b, fromB.pattern());
    assertSame(cached, RegexCache.get(a));
  }

  @Test
  void flagsAreKeyedSeparately() {
    String regex = "abc";
    RegexCache.get(regex, 0);
    Pattern plain = RegexCache.get(regex, 0);
    assertSame(plain, RegexCache.get(regex));

    Pattern ci = RegexCache.get(regex, CASE_INSENSITIVE);
    assertNotSame(plain, ci);
    assertEquals(CASE_INSENSITIVE, ci.flags());
    Pattern ci2 = RegexCache.get(regex, CASE_INSENSITIVE);
    assertEquals(CASE_INSENSITIVE, ci2.flags());
    assertSame(ci2, RegexCache.get(regex, CASE_INSENSITIVE));

    assertEquals(MULTILINE, RegexCache.get(regex, MULTILINE).flags());
    assertEquals(0, RegexCache.get(regex).flags());
  }

  @Test
  void oversizedRegexesAreNeverStored() {
    String atLimit = repeat('a', RegexCache.MAX_REGEX_LENGTH);
    RegexCache.get(atLimit);
    Pattern cached = RegexCache.get(atLimit);
    assertSame(cached, RegexCache.get(atLimit));
    RegexCache.reset();

    String oversized = repeat('b', RegexCache.MAX_REGEX_LENGTH + 1);
    for (int i = 0; i < 5; i++) {
      Pattern p = RegexCache.get(oversized);
      assertSame(oversized, p.pattern());
      assertNotSame(p, RegexCache.get(oversized, CASE_INSENSITIVE));
    }
    assertCounters(0, 10, 0);

    String oversizedInvalid = "(" + oversized;
    PatternSyntaxException expected =
        assertThrows(PatternSyntaxException.class, () -> Pattern.compile(oversizedInvalid));
    PatternSyntaxException actual =
        assertThrows(PatternSyntaxException.class, () -> RegexCache.get(oversizedInvalid));
    assertEquals(expected.getMessage(), actual.getMessage());
    assertEquals(0, RegexCache.admissions());
  }

  @Test
  void failuresAreNeverCached() {
    String bad = "(unclosed";
    PatternSyntaxException expected =
        assertThrows(PatternSyntaxException.class, () -> Pattern.compile(bad));
    for (int i = 0; i < 5; i++) {
      PatternSyntaxException actual =
          assertThrows(PatternSyntaxException.class, () -> RegexCache.get(bad));
      assertEquals(expected.getMessage(), actual.getMessage());
    }
    assertEquals(0, RegexCache.admissions());
    assertEquals(0, RegexCache.hits());
    assertEquals(5, RegexCache.misses());
  }

  @Test
  void failureDoesNotDisturbCandidate() {
    String good = "ok+";
    RegexCache.get(good);
    assertThrows(PatternSyntaxException.class, () -> RegexCache.get("*"));
    RegexCache.get(good);
    assertEquals(1, RegexCache.admissions());
  }

  @Test
  void nullRegexThrowsLikeTheJdk() {
    NullPointerException expected =
        assertThrows(NullPointerException.class, () -> Pattern.compile(null));
    NullPointerException actual =
        assertThrows(NullPointerException.class, () -> RegexCache.get(null));
    assertEquals(expected.getMessage(), actual.getMessage());
    NullPointerException expectedFlags =
        assertThrows(NullPointerException.class, () -> Pattern.compile(null, CASE_INSENSITIVE));
    NullPointerException actualFlags =
        assertThrows(NullPointerException.class, () -> RegexCache.get(null, CASE_INSENSITIVE));
    assertEquals(expectedFlags.getMessage(), actualFlags.getMessage());
    assertCounters(0, 0, 0);
  }

  @Test
  void collidingEntryEvictsOnItsSecondSighting() {
    String a = "a.*";
    String b = findCollision(a);

    RegexCache.get(a);
    Pattern cachedA = RegexCache.get(a);
    assertSame(cachedA, RegexCache.get(a));

    Pattern firstB = RegexCache.get(b);
    assertSame(b, firstB.pattern());
    assertSame(cachedA, RegexCache.get(a));

    Pattern cachedB = RegexCache.get(b);
    assertSame(cachedB, RegexCache.get(b));
    assertEquals(2, RegexCache.admissions());

    Pattern aAgain = RegexCache.get(a);
    assertNotSame(cachedA, aAgain);
    assertSame(a, aAgain.pattern());
  }

  @Test
  void alternatingCollidingRegexesStayCorrect() {
    String a = "q[0-9]";
    String b = findCollision(a);
    for (int i = 0; i < 50; i++) {
      assertSame(a, RegexCache.get(a).pattern());
      assertSame(b, RegexCache.get(b).pattern());
    }
    assertEquals(0, RegexCache.admissions());
  }

  @Test
  void concurrentAccessReturnsCorrectPatterns() throws Exception {
    final String[] shared = new String[64];
    for (int i = 0; i < shared.length; i++) {
      shared[i] = "k" + i + "-[a-z]+";
    }
    final int[] flagChoices = {0, CASE_INSENSITIVE, MULTILINE};
    int threads = 8;
    final int iterations = 20_000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        final int seed = t;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  int checked = 0;
                  for (int i = 0; i < iterations; i++) {
                    int n = (i * 31 + seed * 7) & 63;
                    String regex = (i % 5 == 0) ? new String(shared[n]) : shared[n];
                    int flags = flagChoices[(i + seed) % flagChoices.length];
                    Pattern p = RegexCache.get(regex, flags);
                    if (p.pattern() != regex || p.flags() != flags) {
                      throw new AssertionError("wrong pattern for " + regex + "/" + flags);
                    }
                    boolean expectMatch = flags == CASE_INSENSITIVE || n % 2 == 0;
                    String input = "k" + n + (n % 2 == 0 ? "-abc" : "-ABC");
                    if (p.matcher(input).matches() != expectMatch) {
                      throw new AssertionError("wrong match for " + regex + "/" + flags);
                    }
                    checked++;
                  }
                  return checked;
                }));
      }
      start.countDown();
      for (Future<Integer> f : futures) {
        assertEquals(iterations, f.get(60, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals((long) threads * iterations, RegexCache.hits() + RegexCache.misses());
    assertTrue(RegexCache.hits() > 0);
    assertTrue(RegexCache.admissions() > 0);
  }

  private static String findCollision(String regex) {
    int target = RegexCache.index(regex, 0);
    List<String> tried = new ArrayList<>();
    for (int i = 0; i < 1_000_000; i++) {
      String candidate = "c" + i;
      tried.add(candidate);
      if (candidate != regex && RegexCache.index(candidate, 0) == target) {
        return candidate;
      }
    }
    throw new AssertionError("no collision found");
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  private static void assertCounters(long hits, long misses, long admissions) {
    assertEquals(hits, RegexCache.hits(), "hits");
    assertEquals(misses, RegexCache.misses(), "misses");
    assertEquals(admissions, RegexCache.admissions(), "admissions");
  }
}
