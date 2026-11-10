package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.test.util.DDJavaSpecification;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.mockito.stubbing.Answer;

abstract class AbstractHierarchyMatcherTest extends DDJavaSpecification {
  static {
    TypePoolFacade.registerAsSupplier();
    DDElementMatchers.registerAsSupplier();
  }

  private static final TypePool TYPE_POOL =
      SharedTypePools.typePool(AbstractHierarchyMatcherTest.class.getClassLoader());

  /** Resolves a type through the shared type pool rather than the loaded class. */
  static TypeDescription describe(Class<?> type) {
    return describe(type.getName());
  }

  /** Resolves a type by name through the shared type pool. */
  static TypeDescription describe(String typeName) {
    return TYPE_POOL.describe(typeName).resolve();
  }

  /** Mockito answer throwing a checked exception, like a Spock {@code >> { throw ... }} stub. */
  static Answer<Object> throwing(String message) {
    return invocation -> {
      throw new Exception(message);
    };
  }
}
