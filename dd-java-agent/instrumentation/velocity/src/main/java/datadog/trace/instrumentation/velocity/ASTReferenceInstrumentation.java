package datadog.trace.instrumentation.velocity;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import net.bytebuddy.asm.Advice;
import org.apache.velocity.context.InternalContextAdapter;
import org.apache.velocity.runtime.parser.node.ASTMethod;
import org.apache.velocity.runtime.parser.node.ASTReference;

@AutoService(InstrumenterModule.class)
public class ASTReferenceInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {
  public ASTReferenceInstrumentation() {
    super("velocity");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.velocity.runtime.parser.node.ASTReference";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute")
            .and(isMethod())
            .and(
                takesArgument(0, named("java.lang.Object"))
                    .and(
                        takesArgument(
                            1, named("org.apache.velocity.context.InternalContextAdapter")))),
        ASTReferenceInstrumentation.class.getName() + "$ASTReferenceAdvice");
  }

  public static class ASTReferenceAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.XSS)
    public static void onEnter(
        @Advice.Argument(1) final InternalContextAdapter context,
        @Advice.This final ASTReference self) {
      if (self == null) {
        return;
      }
      final XssModule xssModule = InstrumentationBridge.XSS;
      if (xssModule == null) {
        return;
      }
      if (self.jjtGetParent() instanceof ASTMethod) {
        return;
      }
      Object variable = self.getVariableValue(context, self.getRootString());
      // For cases when you have a variable that is not an string such as the EscapeTool variable
      if (!(variable instanceof String)) {
        return;
      }
      final String charSec = (String) variable;
      final String file = context.getCurrentTemplateName();
      final int line = self.getLine();
      xssModule.onXss(charSec, file, line);
    }
  }
}
