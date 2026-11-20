package datadog.trace.agent.tooling;

import java.security.ProtectionDomain;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stack of advice transformations declared by a particular instrumentation. */
final class AdviceStack implements AgentBuilder.Transformer {
  private static final Logger log = LoggerFactory.getLogger(AdviceStack.class);

  private final AgentBuilder.Transformer[] advices;
  private final ElementMatcher<? super MethodDescription> methodMatcher;
  private final int prerequisiteCount;

  AdviceStack(List<AgentBuilder.Transformer> advices) {
    this(advices, null, 0);
  }

  AdviceStack(
      List<AgentBuilder.Transformer> advices,
      ElementMatcher<? super MethodDescription> methodMatcher,
      int prerequisiteCount) {
    this.advices = advices.toArray(new AgentBuilder.Transformer[0]);
    this.methodMatcher = methodMatcher;
    this.prerequisiteCount = prerequisiteCount;
  }

  AdviceStack(AgentBuilder.Transformer advice) {
    this.advices = new AgentBuilder.Transformer[] {advice};
    this.methodMatcher = null;
    this.prerequisiteCount = 0;
  }

  @Override
  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain pd) {

    for (int i = 0; i < advices.length; i++) {
      // Eager helpers and module reads must run even when no advice matches.
      // Context rewrites are independent too; filter only the method advice below.
      if (i == prerequisiteCount && methodMatcher != null && !mayAdvise(target)) {
        break;
      }
      builder = advices[i].transform(builder, target, classLoader, module, pd);
    }

    return builder;
  }

  /** Only reject definite misses; description or matcher failures retain the stack. */
  private boolean mayAdvise(TypeDescription target) {
    try {
      // DDOutlineTypeStrategy enables full descriptions here; outlines omit parameter annotations
      // and generic signatures needed by some matchers.
      for (MethodDescription method : target.getDeclaredMethods()) {
        if (methodMatcher.matches(method)) {
          return true;
        }
      }
      // Byte Buddy also visits <clinit>, which getDeclaredMethods() omits.
      return methodMatcher.matches(new MethodDescription.Latent.TypeInitializer(target));
    } catch (Throwable failure) {
      log.debug("Method-advice pre-check failed; retaining advice transformation", failure);
      return true;
    }
  }
}
