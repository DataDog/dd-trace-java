package datadog.trace.agent.tooling.muzzle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HelperClassPredicateTest {

  private static HelperClassPredicate predicateWithOwnOutput(String... ownOutput) {
    Set<String> own = new HashSet<>();
    for (String className : ownOutput) {
      own.add(className);
    }
    return new HelperClassPredicate(own::contains);
  }

  @Test
  void instrumentationPackageIsHelperEvenWithoutOwnOutput() {
    // classes under the instrumentation package are ours regardless of where they were compiled
    HelperClassPredicate predicate = predicateWithOwnOutput();
    assertTrue(predicate.isHelperClass("datadog.trace.instrumentation.foo.FooDecorator"));
  }

  @Test
  void allowlistedSharedSubprojectsAreHelpers() {
    // helpers that live in other dd-owned tracer subprojects
    HelperClassPredicate predicate = predicateWithOwnOutput();
    assertTrue(predicate.isHelperClass("datadog.opentelemetry.shim.context.OtelContext"));
    assertTrue(predicate.isHelperClass("datadog.trace.agent.tooling.iast.TaintableEnumeration"));
    assertTrue(predicate.isHelperClass("datadog.trace.agent.tooling.nativeimage.TracerActivation"));
  }

  @Test
  void ownOutputInLibraryPackageIsHelper() {
    // helpers deliberately placed in a library's package are detected via ownOutput
    HelperClassPredicate predicate =
        predicateWithOwnOutput("redis.clients.jedis.JedisClientDecorator");
    assertTrue(predicate.isHelperClass("redis.clients.jedis.JedisClientDecorator"));
    // the real library class in the same package is not ours and must remain a library reference
    assertFalse(predicate.isHelperClass("redis.clients.jedis.Jedis"));
  }

  @Test
  void bootstrapAndJdkAreNeverHelpers() {
    // bootstrap wins even if a class is reported as ownOutput
    HelperClassPredicate predicate =
        predicateWithOwnOutput("datadog.trace.bootstrap.InstrumentationContext");
    assertFalse(predicate.isHelperClass("datadog.trace.bootstrap.InstrumentationContext"));
    assertFalse(predicate.isHelperClass("datadog.trace.api.Config"));
    // datadog.trace.instrumentation.api is a bootstrap sub-package, not an injectable helper
    assertFalse(predicate.isHelperClass("datadog.trace.instrumentation.api.SomeApi"));
    assertFalse(predicate.isHelperClass("java.lang.String"));
    assertFalse(predicate.isHelperClass("javax.servlet.http.HttpServletRequest"));
    assertFalse(predicate.isHelperClass("org.slf4j.Logger"));
    assertFalse(predicate.isHelperClass("datadog.slf4j.Logger"));
  }

  @Test
  void plainLibraryClassIsNotHelper() {
    HelperClassPredicate predicate = predicateWithOwnOutput();
    assertFalse(predicate.isHelperClass("org.apache.http.HttpRequest"));
    assertFalse(predicate.isHelperClass("com.datastax.oss.driver.api.core.CqlSession"));
  }

  @Test
  void isBootstrapMatchesBootstrapPrefixesAndJdk() {
    assertTrue(HelperClassPredicate.isBootstrap("datadog.trace.instrumentation.api.X"));
    assertTrue(HelperClassPredicate.isBootstrap("datadog.trace.bootstrap.Y"));
    assertTrue(HelperClassPredicate.isBootstrap("datadog.trace.api.Z"));
    assertTrue(HelperClassPredicate.isBootstrap("java.util.List"));
    assertTrue(HelperClassPredicate.isBootstrap("jdk.internal.Foo"));
    assertFalse(HelperClassPredicate.isBootstrap("datadog.trace.instrumentation.foo.Bar"));
    assertFalse(HelperClassPredicate.isBootstrap("com.example.Lib"));
  }
}
