package datadog.trace.api.cache;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.test.util.GCUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class FixedSizeCacheTest extends DDJavaSpecification {

  private static final TKey ID1 = new TKey(1, 1, "one");

  @TableTest({
    "scenario  | capacity   ",
    "min value | -2147483648",
    "negative  | -1         ",
    "zero      | 0          "
  })
  void invalidCapacitiesAreRejected(int capacity) {
    assertThrows(IllegalArgumentException.class, () -> DDCaches.newFixedSizeCache(capacity));
  }

  @Test
  void cacheCanBeExplicitlyCleared() {
    DDCache<String, String> fsCache = DDCaches.newFixedSizeCache(15);

    fsCache.computeIfAbsent("test-key", key -> "first-value");

    assertEquals("first-value", fsCache.computeIfAbsent("test-key", key -> "second-value"));

    fsCache.clear();

    assertEquals("second-value", fsCache.computeIfAbsent("test-key", key -> "second-value"));
  }

  @TableTest({
    "scenario                             | keyHash | keyEq | keyString | value        | keys                  | count",
    "used the cached tk1                  | 1       | 1     | foo       | one_value    | [one, six, ten]       | 3    ",
    "used the cached tk6                  | 1       | 6     | foo       | six_value    | [one, six, ten]       | 3    ",
    "used the cached tk10                 | 1       | 10    | foo       | ten_value    | [one, six, ten]       | 3    ",
    "used the cached tk6 with same hash   | 6       | 6     | foo       | six_value    | [one, six, ten]       | 3    ",
    "create new value in an occupied slot | 1       | 11    | eleven    | eleven_value | [eleven, six, ten]    | 4    ",
    "create new value in empty slot       | 4       | 4     | four      | four_value   | [one, four, six, ten] | 4    ",
    "do nothing                           |         |       |           |              | [one, six, ten]       | 3    "
  })
  void fixedSizeShouldStoreAndRetrieveValues(
      Integer keyHash,
      Integer keyEq,
      String keyString,
      String value,
      List<String> keys,
      int count) {
    DDCache<TKey, String> fsCache = DDCaches.newFixedSizeCache(15);
    AtomicInteger creationCount = new AtomicInteger(0);
    TVC tvc = new TVC(creationCount);
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(new TKey(1, 1, "one"), tvc);
    fsCache.computeIfAbsent(new TKey(6, 6, "six"), tvc);
    fsCache.computeIfAbsent(new TKey(10, 10, "ten"), tvc);

    assertEquals(value, fsCache.computeIfAbsent(tKey(keyHash, keyEq, keyString), tvc));
    assertEquals(count, creationCount.get());

    assertEquals(keys, visitedKeys(fsCache));
  }

  @TableTest({
    "scenario                             | keyHash | keyEq | keyString | value        | keys                  | count",
    "used the cached tk1                  | 1       | 1     | foo       | one_value    | [one, six, ten]       | 3    ",
    "used the cached tk6                  | 1       | 6     | foo       | six_value    | [one, six, ten]       | 3    ",
    "used the cached tk10                 | 1       | 10    | foo       | ten_value    | [one, six, ten]       | 3    ",
    "used the cached tk6 with same hash   | 6       | 6     | foo       | six_value    | [one, six, ten]       | 3    ",
    "create new value in an occupied slot | 1       | 11    | eleven    | eleven_value | [eleven, six, ten]    | 4    ",
    "create new value in empty slot       | 4       | 4     | four      | four_value   | [one, four, six, ten] | 4    ",
    "do nothing                           |         |       |           |              | [one, six, ten]       | 3    "
  })
  void fixedSizeWithArrayKeysShouldStoreAndRetrieveValues(
      Integer keyHash,
      Integer keyEq,
      String keyString,
      String value,
      List<String> keys,
      int count) {
    DDCache<TKey[], String> fsCache = DDCaches.newFixedSizeArrayKeyCache(15);
    AtomicInteger creationCount = new AtomicInteger(0);
    TVCA tvc = new TVCA(creationCount);
    // need to offset key hashes by 31 to achieve the same collisions using array hashing
    TKey[] tk1 = {new TKey(1 - 31, 1, "one")};
    TKey[] tk6 = {new TKey(6 - 31, 6, "six")};
    TKey[] tk10 = {new TKey(10 - 31, 10, "ten")};
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(tk1, tvc);
    fsCache.computeIfAbsent(tk6, tvc);
    fsCache.computeIfAbsent(tk10, tvc);
    TKey[] tk = keyHash == null ? null : new TKey[] {new TKey(keyHash - 31, keyEq, keyString)};

    assertEquals(value, fsCache.computeIfAbsent(tk, tvc));
    assertEquals(count, creationCount.get());

    List<String> visited = new ArrayList<>();
    fsCache.visit(
        (key, val) -> {
          if ((key[0] + "_value").equals(val)) {
            visited.add(key[0].toString());
          }
        });
    assertEquals(keys, visited);
  }

  @TableTest({
    "scenario                                         | keyHash | keyEq | keyString | value     | keys     | count",
    "used the cached id1                              | 1       | 1     | one       | one_value | [one]    | 1    ",
    "create new value for key with different identity | 1       | 1     | 1         | 1_value   | [one, 1] | 2    ",
    "create new value for new key                     | 6       | 6     | 6         | 6_value   | [one, 6] | 2    ",
    "do nothing                                       |         |       |           |           | [one]    | 1    "
  })
  void identityCacheShouldStoreAndRetrieveValues(
      Integer keyHash,
      Integer keyEq,
      String keyString,
      String value,
      List<String> keys,
      int count) {
    DDCache<TKey, String> fsCache = DDCaches.newFixedSizeIdentityCache(256);
    AtomicInteger creationCount = new AtomicInteger(0);
    TVC tvc = new TVC(creationCount);
    fsCache.computeIfAbsent(ID1, tvc);

    // (only use one key because we can't control the identity hash: more keys might overwrite
    // an earlier slot if rehashing cycles to the same slots, breaking test assumption that all
    // the initial keys are allocated to distinct slots)

    TKey tk = identityKey(keyHash, keyEq, keyString);

    assertEquals(value, fsCache.computeIfAbsent(tk, tvc));
    assertEquals(count, creationCount.get());

    assertEquals(sorted(keys), sorted(visitedKeys(fsCache)));
  }

  @TableTest({
    "scenario                                         | keyHash | keyEq | keyString | value     | keys     | count",
    "used the cached id1                              | 1       | 1     | one       | one_value | [one]    | 1    ",
    "create new value for key with different identity | 1       | 1     | 1         | 1_value   | [one, 1] | 2    ",
    "create new value for new key                     | 6       | 6     | 6         | 6_value   | [one, 6] | 2    ",
    "do nothing                                       |         |       |           |           | [one]    | 1    "
  })
  void weakKeyCacheShouldStoreAndRetrieveValues(
      Integer keyHash,
      Integer keyEq,
      String keyString,
      String value,
      List<String> keys,
      int count) {
    DDCache<TKey, String> fsCache = DDCaches.newFixedSizeWeakKeyCache(256);
    AtomicInteger creationCount = new AtomicInteger(0);
    TVC tvc = new TVC(creationCount);
    fsCache.computeIfAbsent(ID1, tvc);

    // (only use one key because we can't control the identity hash: more keys might overwrite
    // an earlier slot if rehashing cycles to the same slots, breaking test assumption that all
    // the initial keys are allocated to distinct slots)

    TKey tk = identityKey(keyHash, keyEq, keyString);

    assertEquals(value, fsCache.computeIfAbsent(tk, tvc));
    assertEquals(count, creationCount.get());

    assertEquals(sorted(keys), sorted(visitedKeys(fsCache)));
  }

  @Test
  void weakKeyCacheDoesNotHoldOntoKey() throws InterruptedException {
    DDCache<Object, String> fsCache = DDCaches.newFixedSizeWeakKeyCache(15);
    Object testKey = new Object();
    WeakReference<Object> testRef = new WeakReference<>(testKey);
    fsCache.computeIfAbsent(testKey, key -> "oldValue");

    assertEquals("oldValue", fsCache.computeIfAbsent(testKey, key -> "newValue"));

    testKey = null;

    GCUtils.awaitGC(testRef);
  }

  @TableTest({
    "scenario                            | keyHash | keyEq | keyString | value        | keys                    | count",
    "used the cached tk1                 | 1       | 1     | foo       | one_value    | [one, six, ten]         | 3    ",
    "create new value for tk6 equality   | 1       | 6     | foo       | foo_value    | [one, foo, six, ten]    | 4    ",
    "create new value for tk10 equality  | 1       | 10    | foo       | foo_value    | [one, foo, six, ten]    | 4    ",
    "used the cached tk6                 | 6       | 6     | foo       | six_value    | [one, six, ten]         | 3    ",
    "create new value for colliding hash | 1       | 11    | eleven    | eleven_value | [one, eleven, six, ten] | 4    ",
    "create new value for new hash       | 4       | 4     | four      | four_value   | [one, four, six, ten]   | 4    ",
    "do nothing                          |         |       |           |              | [one, six, ten]         | 3    "
  })
  void chmCacheShouldStoreAndRetrieveValues(
      Integer keyHash,
      Integer keyEq,
      String keyString,
      String value,
      List<String> keys,
      int count) {
    DDCache<TKey, String> fsCache = DDCaches.newUnboundedCache(15);
    AtomicInteger creationCount = new AtomicInteger(0);
    TVC tvc = new TVC(creationCount);
    fsCache.computeIfAbsent(new TKey(1, 1, "one"), tvc);
    fsCache.computeIfAbsent(new TKey(6, 6, "six"), tvc);
    fsCache.computeIfAbsent(new TKey(10, 10, "ten"), tvc);

    assertEquals(value, fsCache.computeIfAbsent(tKey(keyHash, keyEq, keyString), tvc));
    assertEquals(count, creationCount.get());

    assertEquals(sorted(keys), sorted(visitedKeys(fsCache)));
  }

  @ParameterizedTest(name = "should handle concurrent usage {0}")
  @MethodSource("shouldHandleConcurrentUsageArguments")
  void shouldHandleConcurrentUsage(String cacheType, IntFunction<DDCache<TKey, String>> cacheImpl)
      throws Exception {
    int numThreads = 5;
    int numInsertions = 100000;
    CountDownLatch started = new CountDownLatch(numThreads);
    CountDownLatch runTest = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    try {
      DDCache<TKey, String> fsCache = cacheImpl.apply(64);
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < numThreads; t++) {
        futures.add(
            executor.submit(
                () -> {
                  ThreadLocalRandom random = ThreadLocalRandom.current();
                  started.countDown();
                  runTest.await();
                  for (int i = 0; i < numInsertions; i++) {
                    int hash = random.nextInt();
                    String string = String.valueOf(hash);
                    TKey tkey = new TKey(hash, hash, string);
                    assertEquals(
                        string + "_value",
                        fsCache.computeIfAbsent(tkey, key -> key.string + "_value"));
                  }
                  return null;
                }));
      }
      started.await();
      runTest.countDown();

      for (Future<?> future : futures) {
        future.get(30, SECONDS); // the test is really fast locally, but I don't know how fast CI is
      }
    } finally {
      executor.shutdownNow();
    }
  }

  static Stream<Arguments> shouldHandleConcurrentUsageArguments() {
    return Stream.of(
        arguments("fixed size", (IntFunction<DDCache<TKey, String>>) DDCaches::newFixedSizeCache),
        arguments("unbounded", (IntFunction<DDCache<TKey, String>>) DDCaches::newUnboundedCache),
        arguments(
            "fixed size weighted",
            (IntFunction<DDCache<TKey, String>>)
                capacity -> DDCaches.newFixedSizeWeightedCache(capacity, String::length, 1000)));
  }

  @TableTest({
    "scenario                              | h | m      | n     | s      | value        | overwritten | count",
    "used the cached tk1                   | 1 | 18527  | 76397 | foo    | one_17_29    | false       | 3    ",
    "used the cached tk6                   | 1 | -14685 | 55725 | foo    | six_47_11    | false       | 3    ",
    "used the cached tk10                  | 1 | -19379 | 1712  | foo    | ten_10_66    | false       | 3    ",
    "create new value in 1st occupied slot | 1 | 13     | 37    | eleven | eleven_13_37 | true        | 4    ",
    "create new value in empty slot        | 4 | 88     | 11    | four   | four_88_11   | false       | 4    ",
    "do nothing                            | 0 | 0      | 0     |        |              | false       | 3    "
  })
  void fixedSizePartialKeyShouldStoreAndRetrieveValues(
      int h, int m, int n, String s, String value, boolean overwritten, int count) {
    DDPartialKeyCache<TKeyB, String> fsCache = DDCaches.newFixedSizePartialKeyCache(15);
    AtomicInteger creationCount = new AtomicInteger(0);
    TKeyBHash tkbh = new TKeyBHash();
    TKeyBComparator tkbc = new TKeyBComparator();
    TKeyBProducer tkbp = new TKeyBProducer(creationCount);
    // we will use m and n in the hash, compare and produce functions
    TKeyB tk1 = tKeyB(1, 17, 29, "one");
    TKeyB tk6 = tKeyB(1, 47, 11, "six");
    TKeyB tk10 = tKeyB(1, 10, 66, "ten");
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(tk1, 17, 29, tkbh, tkbc, tkbp);
    fsCache.computeIfAbsent(tk6, 47, 11, tkbh, tkbc, tkbp);
    fsCache.computeIfAbsent(tk10, 10, 66, tkbh, tkbc, tkbp);
    TKeyB tk = h == 0 ? null : tKeyB(h, m, n, s);

    String v1 = fsCache.computeIfAbsent(tk, m, n, tkbh, tkbc, tkbp);

    assertEquals(value, v1);
    assertEquals(count, creationCount.get());
    assertEquals("one_17_29", fsCache.computeIfAbsent(tk1, 17, 29, tkbh, tkbc, tkbp));
    assertEquals("six_47_11", fsCache.computeIfAbsent(tk6, 47, 11, tkbh, tkbc, tkbp));
    assertEquals("ten_10_66", fsCache.computeIfAbsent(tk10, 10, 66, tkbh, tkbc, tkbp));
    assertEquals(count + (overwritten ? 1 : 0), creationCount.get());
  }

  @TableTest({
    "scenario                             | keyHash | keyEq | keyString | value        | keys                  | count",
    "used the cached tk1                  | 1       | 1     | foo       | one_value    | [one, six, ten]       | 3    ",
    "used the cached tk6                  | 1       | 6     | foo       | six_value    | [one, six, ten]       | 3    ",
    "used the cached tk10                 | 1       | 10    | foo       | ten_value    | [one, six, ten]       | 3    ",
    "used the cached tk6 with same hash   | 6       | 6     | foo       | six_value    | [one, six, ten]       | 3    ",
    "create new value in an occupied slot | 1       | 11    | eleven    | eleven_value | [eleven, six, ten]    | 4    ",
    "create new value in empty slot       | 4       | 4     | four      | four_value   | [one, four, six, ten] | 4    ",
    "do nothing                           |         |       |           |              | [one, six, ten]       | 3    "
  })
  void weightedCacheShouldStoreAndRetrieveValues(
      Integer keyHash,
      Integer keyEq,
      String keyString,
      String value,
      List<String> keys,
      int count) {
    DDCache<TKey, String> fsCache = DDCaches.newFixedSizeWeightedCache(15, String::length, 50);
    AtomicInteger creationCount = new AtomicInteger(0);
    TVC tvc = new TVC(creationCount);
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(new TKey(1, 1, "one"), tvc);
    fsCache.computeIfAbsent(new TKey(6, 6, "six"), tvc);
    fsCache.computeIfAbsent(new TKey(10, 10, "ten"), tvc);

    assertEquals(value, fsCache.computeIfAbsent(tKey(keyHash, keyEq, keyString), tvc));
    assertEquals(count, creationCount.get());

    assertEquals(keys, visitedKeys(fsCache));
  }

  @Test
  void weightedCacheShouldRespectTotalWeightLimit() {
    DDCache<TKey, String> fsCache = DDCaches.newFixedSizeWeightedCache(15, String::length, 33);
    String tenLetters = "ABCDEFGHIJ";
    String twentyLetters = "ABCDEFGHIJABCDEFGHIJ";
    String thirtyLetters = "ABCDEFGHIJABCDEFGHIJABCDEFGHIJ";
    String fortyLetters = "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ";
    String tenDigits = "0123456789";
    String fortyDashes = "----------------------------------------";
    TKey tk1 = new TKey(1, 1, "one");
    TKey tk6 = new TKey(6, 6, "six");
    TKey tk10 = new TKey(10, 10, "ten");
    TKey tk11 = new TKey(1, 11, "eleven");

    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(tk1, key -> tenDigits);
    fsCache.computeIfAbsent(tk6, key -> tenDigits);
    fsCache.computeIfAbsent(tk10, key -> tenDigits);

    // all elements cached, cache is at weight limit
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk1, key -> fortyDashes));
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk6, key -> fortyDashes));
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk10, key -> fortyDashes));

    // replace tk1 entry with a new tk11 entry of the same size
    fsCache.computeIfAbsent(tk11, key -> tenLetters);

    // cache reflects change, cache is still at weight limit
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk6, key -> fortyDashes));
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk10, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk11, key -> fortyDashes));

    // replace tk11 entry with a new tk1 entry that has a longer string
    fsCache.computeIfAbsent(tk1, key -> twentyLetters);

    // tk1 and tk6 used up the weight limit, so tk10 has been evicted
    assertEquals(twentyLetters, fsCache.computeIfAbsent(tk1, key -> fortyDashes));
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk6, key -> fortyDashes));

    // replace recently evicted tk10 with same-length string
    fsCache.computeIfAbsent(tk10, key -> tenLetters);

    // now tk10 and tk1 use up the weight limit, so tk6 has been evicted
    assertEquals(twentyLetters, fsCache.computeIfAbsent(tk1, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk10, key -> fortyDashes));

    // replace recently evicted tk6 with same-length string
    fsCache.computeIfAbsent(tk6, key -> tenLetters);

    // keeping tk6 and tk10 means no space for tk1, so it's been evicted
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk6, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk10, key -> fortyDashes));

    // replace recently evicted tk1 with shorter string
    fsCache.computeIfAbsent(tk1, key -> tenLetters);

    // all elements cached, cache is at weight limit
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk1, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk6, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk10, key -> fortyDashes));

    // values already over the weight limit are never cached
    fsCache.computeIfAbsent(tk11, key -> fortyLetters);

    // previous elements still cached, cache is at weight limit
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk1, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk6, key -> fortyDashes));
    assertEquals(tenLetters, fsCache.computeIfAbsent(tk10, key -> fortyDashes));

    // add new tk11 entry that uses up the entire weight limit
    fsCache.computeIfAbsent(tk11, key -> thirtyLetters);

    // tk11 element cached, cache is at weight limit
    assertEquals(thirtyLetters, fsCache.computeIfAbsent(tk11, key -> fortyDashes));

    // repopulate the original elements with different strings
    fsCache.computeIfAbsent(tk1, key -> tenDigits);
    fsCache.computeIfAbsent(tk6, key -> tenDigits);
    fsCache.computeIfAbsent(tk10, key -> tenDigits);

    // all elements cached, cache is at weight limit
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk1, key -> fortyDashes));
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk6, key -> fortyDashes));
    assertEquals(tenDigits, fsCache.computeIfAbsent(tk10, key -> fortyDashes));
  }

  private static TKey tKey(Integer hash, Integer eq, String string) {
    return hash == null ? null : new TKey(hash, eq, string);
  }

  /** Same as {@link #tKey} but returns the cached {@link #ID1} instance when the fields match. */
  private static TKey identityKey(Integer hash, Integer eq, String string) {
    TKey key = tKey(hash, eq, string);
    // reuse the cached id1 instance when the row describes it, as identity cannot be rebuilt
    if (key != null && key.hash == ID1.hash && key.eq == ID1.eq && key.string.equals(ID1.string)) {
      return ID1;
    }
    return key;
  }

  private static TKeyB tKeyB(int hash, int m, int n, String s) {
    return new TKeyB((hash * m) + n, s);
  }

  /** Collects the keys whose cached value matches the value produced by {@link TVC}. */
  private static List<String> visitedKeys(DDCache<TKey, String> cache) {
    List<String> visited = new ArrayList<>();
    cache.visit(
        (key, value) -> {
          if ((key + "_value").equals(value)) {
            visited.add(key.toString());
          }
        });
    return visited;
  }

  private static List<String> sorted(List<String> list) {
    List<String> copy = new ArrayList<>(list);
    copy.sort(null);
    return copy;
  }

  private static class TVC implements Function<TKey, String> {
    private final AtomicInteger count;

    TVC(AtomicInteger count) {
      this.count = count;
    }

    @Override
    public String apply(TKey key) {
      count.incrementAndGet();
      return key.string + "_value";
    }
  }

  private static class TVCA implements Function<TKey[], String> {
    private final AtomicInteger count;

    TVCA(AtomicInteger count) {
      this.count = count;
    }

    @Override
    public String apply(TKey[] key) {
      count.incrementAndGet();
      return key[0].string + "_value";
    }
  }

  private static class TKeyBHash implements DDPartialKeyCache.Hasher<TKeyB> {
    @Override
    public int apply(TKeyB key, int m, int n) {
      return (key.hash - n) / m;
    }
  }

  private static class TKeyBComparator implements DDPartialKeyCache.Comparator<TKeyB, String> {
    @Override
    public boolean test(TKeyB key, int m, int n, String s) {
      return (key.string + "_" + m + "_" + n).equals(s)
          || (key.string.hashCode() * m) + n == s.hashCode();
    }
  }

  private static class TKeyBProducer implements DDPartialKeyCache.Producer<TKeyB, String> {
    private final AtomicInteger count;

    TKeyBProducer(AtomicInteger count) {
      this.count = count;
    }

    @Override
    public String apply(TKeyB key, int hash, int m, int n) {
      count.incrementAndGet();
      return key.string + "_" + m + "_" + n;
    }
  }

  private static class TKeyB {
    protected final int hash;
    protected final String string;

    TKeyB(int hash, String string) {
      this.hash = hash;
      this.string = string;
    }
  }

  private static class TKey extends TKeyB {
    private final int eq;

    TKey(int hash, int eq, String string) {
      super(hash, string);
      this.eq = eq;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TKey tKey = (TKey) o;
      return eq == tKey.eq;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public String toString() {
      return string;
    }
  }
}
