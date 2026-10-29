package datadog.trace.instrumentation.weaver;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Preserves declaration identity when Weaver converts TestName to name-only outcomes. */
@AutoService(InstrumenterModule.class)
public class WeaverTestNameInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {
  public WeaverTestNameInstrumentation() {
    super("ci-visibility", "weaver");
  }

  @Override
  public String instrumentedType() {
    return "weaver.TestName";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    // Weaver 0.11 removed custom string tags, so there is no exclusion metadata to bridge.
    return declaresMethod(named("tagged").and(takesArguments(String.class)));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(takesArgument(2, named("scala.collection.immutable.Set"))),
        WeaverTestNameInstrumentation.class.getName() + "$DeclarationIdentityAdvice");
  }

  public static class DeclarationIdentityAdvice {
    @SuppressFBWarnings(
        value = "DM_STRING_CTOR",
        justification =
            "Equal display names need distinct identities to carry declaration metadata")
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void preserveDeclarationIdentity(
        @Advice.Argument(value = 0, readOnly = false) String name) {
      // Copy only the String object, preserving its value and all native name filtering.
      // Test.apply/pure carries this same reference from TestName into TestOutcome.
      // Unannotated copies need their own identity too: copy(tags = Set.empty) may remove tags.
      name = new String(name);
    }
  }
}
