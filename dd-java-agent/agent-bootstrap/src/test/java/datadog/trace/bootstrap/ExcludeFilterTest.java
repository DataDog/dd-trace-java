package datadog.trace.bootstrap;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.add;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExcludeFilterTest extends DDJavaSpecification {

  @ParameterizedTest(name = "test empty ExcludeFilter {0}")
  @EnumSource(ExcludeType.class)
  void testEmptyExcludeFilter(ExcludeType type) {
    // setup
    One one = new One();
    String oneName = One.class.getName();

    // expect
    assertFalse(exclude(type, one));
    assertFalse(exclude(type, oneName));
  }

  @ParameterizedTest(name = "test ExcludeFilter {0}")
  @EnumSource(ExcludeType.class)
  void testExcludeFilter(ExcludeType type) {
    // setup
    Another another = new Another();
    String anotherName = Another.class.getName();
    YetAnother yetAnother = new YetAnother();
    String yetAnotherName = YetAnother.class.getName();
    Map<ExcludeType, Set<String>> excludedTypes = new HashMap<>();
    excludedTypes.put(RUNNABLE, singleton(anotherName));
    excludedTypes.put(EXECUTOR, singleton(yetAnotherName));
    excludedTypes.put(RUNNABLE_FUTURE, new HashSet<>(asList(anotherName, yetAnotherName)));
    add(excludedTypes);
    boolean anotherExcluded = type == RUNNABLE || type == RUNNABLE_FUTURE;
    boolean yetAnotherExcluded = type == EXECUTOR || type == RUNNABLE_FUTURE;

    // expect
    assertEquals(anotherExcluded, exclude(type, another));
    assertEquals(anotherExcluded, exclude(type, anotherName));
    assertEquals(yetAnotherExcluded, exclude(type, yetAnother));
    assertEquals(yetAnotherExcluded, exclude(type, yetAnotherName));
  }

  @Test
  void excludeOkHttpTaskRunnerWorkerFromRunnablePropagation() {
    String taskRunnerWorker = "okhttp3.internal.concurrent.TaskRunner$runnable$1";

    // expect
    assertTrue(exclude(RUNNABLE, taskRunnerWorker));
    assertFalse(exclude(EXECUTOR, taskRunnerWorker));
  }

  static class One {}

  static class Another {}

  static class YetAnother {}
}
