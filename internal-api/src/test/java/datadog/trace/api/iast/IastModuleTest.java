package datadog.trace.api.iast;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.api.iast.sink.HstsMissingHeaderModule;
import datadog.trace.api.iast.sink.HttpResponseHeaderModule;
import datadog.trace.api.iast.sink.InsecureCookieModule;
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule;
import datadog.trace.api.iast.sink.NoSameSiteCookieModule;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.api.iast.sink.XContentTypeModule;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IastModuleTest {

  private static final Set<Class<?>> OPT_OUT_MODULES =
      new HashSet<>(
          Arrays.asList(
              HttpResponseHeaderModule.class,
              InsecureCookieModule.class,
              NoHttpOnlyCookieModule.class,
              NoSameSiteCookieModule.class,
              HstsMissingHeaderModule.class,
              XContentTypeModule.class,
              ApplicationModule.class,
              StacktraceLeakModule.class));

  @Test
  void exceptionsAreLogged() {
    IastModule module = new IastModule() {};

    assertDoesNotThrow(() -> module.onUnexpectedException("hello", new Error("Boom!!!")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testOptOutModulesArguments")
  void testOptOutModules(String moduleName, Class<?> clazz) {
    boolean expected = OPT_OUT_MODULES.contains(clazz);

    boolean enabled = clazz.getDeclaredAnnotation(IastModule.OptOut.class) != null;

    assertEquals(expected, enabled);
  }

  static Stream<Arguments> testOptOutModulesArguments() {
    return InstrumentationBridge.getIastModules().stream()
        .map(clazz -> arguments(clazz.getSimpleName(), clazz));
  }
}
