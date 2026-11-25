package datadog.trace.agent.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.agent.tooling.bytebuddy.DDLocationStrategy;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ResourceLocatingTest extends DDJavaSpecification {
  private static final AtomicReference<String> lastLookup = new AtomicReference<>();

  private static final ClassLoader childLoader =
      new ClassLoader(ResourceLocatingTest.class.getClassLoader()) {
        @Override
        public URL getResource(String name) {
          lastLookup.set(name);
          // do not delegate resource lookup
          return findResource(name);
        }
      };

  @AfterEach
  void resetLastLookup() {
    lastLookup.set(null);
  }

  static Stream<Arguments> findsResourcesFromParentClassloaderArguments() {
    return Stream.of(
        arguments("DDLocationStrategy", new DDLocationStrategy(), true),
        arguments(
            "LocationStrategy.ForClassLoader.STRONG",
            AgentBuilder.LocationStrategy.ForClassLoader.STRONG,
            false));
  }

  @ParameterizedTest(name = "finds resources from parent classloader {0}")
  @MethodSource("findsResourcesFromParentClassloaderArguments")
  void findsResourcesFromParentClassloader(
      String description,
      AgentBuilder.LocationStrategy locationStrategy,
      boolean usesProvidedClassloader)
      throws IOException {
    ClassFileLocator locator = locationStrategy.classFileLocator(childLoader, null);

    assertEquals(usesProvidedClassloader, locator.locate("java/lang/Object").isResolved());
    // lastLookup verifies that the given classloader is only used when expected
    assertEquals(usesProvidedClassloader ? null : "java/lang/Object.class", lastLookup.get());

    assertFalse(locator.locate("java/lang/InvalidClass").isResolved());
    assertEquals("java/lang/InvalidClass.class", lastLookup.get());
  }
}
