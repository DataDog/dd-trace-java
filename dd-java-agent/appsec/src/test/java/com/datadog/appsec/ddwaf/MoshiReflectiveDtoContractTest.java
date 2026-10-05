package com.datadog.appsec.ddwaf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.report.AppSecEventWrapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the no-arg constructor contract documented on {@link WAFResultData} and {@link
 * AppSecEventWrapper}, which the Moshi reflective adapters depend on.
 *
 * <p>The runtime that exposes a breach of it — one that does not resolve the {@code
 * jdk.unsupported} module, leaving Moshi no {@code sun.misc.Unsafe} fallback — cannot be simulated
 * from inside a running JVM, so this asserts the contract directly instead.
 */
class MoshiReflectiveDtoContractTest {

  /**
   * Every class reached by the reflective adapters in {@code WAFModule} and {@code
   * AppSecEventWrapper}.
   */
  private static final List<Class<?>> REFLECTIVELY_INSTANTIATED =
      Arrays.asList(
          WAFResultData.class,
          WAFResultData.Rule.class,
          WAFResultData.RuleMatch.class,
          WAFResultData.Parameter.class,
          WAFResultData.MatchInfo.class,
          AppSecEventWrapper.class,
          AppSecEvent.class);

  @Test
  void everyReflectivelyBoundDtoDeclaresANoArgConstructor() {
    for (Class<?> clazz : REFLECTIVELY_INSTANTIATED) {
      assertDoesNotThrow(
          () -> {
            clazz.getDeclaredConstructor();
          },
          clazz.getName() + " must declare a no-arg constructor for Moshi");
    }
  }
}
