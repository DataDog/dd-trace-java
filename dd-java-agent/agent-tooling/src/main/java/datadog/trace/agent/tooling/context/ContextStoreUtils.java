package datadog.trace.agent.tooling.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

final class ContextStoreUtils {

  static AgentBuilder.Transformer wrapVisitor(final AsmVisitorWrapper visitor) {
    return new AgentBuilder.Transformer() {
      @Override
      public DynamicType.Builder<?> transform(
          final DynamicType.Builder<?> builder,
          final TypeDescription typeDescription,
          final ClassLoader classLoader,
          final JavaModule module) {
        return builder.visit(visitor);
      }
    };
  }

  static Map<String, String> unpackContextStore(
      Map<ElementMatcher<ClassLoader>, Map<String, String>> matchedContextStores) {
    if (matchedContextStores.isEmpty()) {
      return Collections.emptyMap();
    } else if (matchedContextStores.size() == 1) {
      return matchedContextStores.entrySet().iterator().next().getValue();
    } else {
      Map<String, String> contextStore = new HashMap<>();
      for (Map.Entry<ElementMatcher<ClassLoader>, Map<String, String>> matcherAndStores :
          matchedContextStores.entrySet()) {
        contextStore.putAll(matcherAndStores.getValue());
      }
      return contextStore;
    }
  }
}
