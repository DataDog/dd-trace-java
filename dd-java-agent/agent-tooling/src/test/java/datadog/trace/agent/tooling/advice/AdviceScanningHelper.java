package datadog.trace.agent.tooling.advice;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import java.util.Collection;
import net.bytebuddy.pool.TypePool;

final class AdviceScanningHelper {
  static final class Dependency {
    static String field;

    Dependency() {}

    String method(String value) {
      return value;
    }
  }

  static final class MuzzleHelper {
    static Collection<? extends Reference> compileReferences() {
      return singletonList(new Reference.Builder("library.Type").build());
    }
  }

  static final class MuzzleReferenceProvider implements ReferenceProvider {
    @Override
    public Iterable<Reference> buildReferences(TypePool typePool) {
      return emptyList();
    }
  }

  static Class<?> localClass() {
    class Local {}
    return Local.class;
  }

  static Class<?> anonymousClass() {
    return new Object() {}.getClass();
  }
}
