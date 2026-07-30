package datadog.trace.instrumentation.beanshell;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_STATIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.CodeInjectionModule;
import datadog.trace.api.iast.sink.SsrfModule;
import java.io.Reader;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class BeanShellInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public BeanShellInstrumentation() {
    super("beanshell");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"bsh.Interpreter", "bsh.Remote"};
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("bsh.Interpreter")
          .withMethod(
              new String[0],
              EXPECTS_PUBLIC | EXPECTS_NON_STATIC,
              "eval",
              "Ljava/lang/Object;",
              "Ljava/lang/String;",
              "Lbsh/NameSpace;")
          .withMethod(
              new String[0],
              EXPECTS_PUBLIC | EXPECTS_NON_STATIC,
              "eval",
              "Ljava/lang/Object;",
              "Ljava/io/Reader;",
              "Lbsh/NameSpace;",
              "Ljava/lang/String;")
          .build(),
      new Reference.Builder("bsh.Remote")
          .withMethod(
              new String[0],
              EXPECTS_PUBLIC | EXPECTS_STATIC,
              "eval",
              "I",
              "Ljava/lang/String;",
              "Ljava/lang/String;")
          .build(),
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // bsh.Interpreter.eval(String, NameSpace): entry point that eval(String) also delegates to.
    // It builds the Reader internally, but bsh.* is excluded from call-site instrumentation
    // (iast_exclusion.trie) so that reader is never tainted; we inspect the String arg directly.
    transformer.applyAdvice(
        named("eval")
            .and(isMethod())
            .and(
                takesArguments(2)
                    .and(takesArgument(0, String.class))
                    .and(takesArgument(1, named("bsh.NameSpace")))),
        BeanShellInstrumentation.class.getName() + "$StringEvalAdvice");
    // bsh.Interpreter.eval(Reader, NameSpace, String): shared core reached by public eval(Reader).
    // Only reports when the caller supplied a tainted Reader; the Reader built internally by
    // eval(String, NameSpace) is untainted, so the String path above does not double-report.
    transformer.applyAdvice(
        named("eval")
            .and(isMethod())
            .and(
                takesArguments(3)
                    .and(takesArgument(0, Reader.class))
                    .and(takesArgument(1, named("bsh.NameSpace")))
                    .and(takesArgument(2, String.class))),
        BeanShellInstrumentation.class.getName() + "$EvalAdvice");
    // bsh.Remote.eval(String url, String text)
    transformer.applyAdvice(
        named("eval").and(isMethod()).and(takesArguments(String.class, String.class)),
        BeanShellInstrumentation.class.getName() + "$RemoteEvalAdvice");
  }

  public static class StringEvalAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.CODE_INJECTION)
    public static void onEnter(@Advice.Argument(0) final String statements) {
      if (statements == null) {
        return;
      }
      final CodeInjectionModule codeInjectionModule = InstrumentationBridge.CODE_INJECTION;
      if (codeInjectionModule == null) {
        return;
      }
      codeInjectionModule.onEval(statements);
    }
  }

  public static class EvalAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.CODE_INJECTION)
    public static void onEnter(@Advice.Argument(0) final Reader reader) {
      if (reader == null) {
        return;
      }
      final CodeInjectionModule codeInjectionModule = InstrumentationBridge.CODE_INJECTION;
      if (codeInjectionModule == null) {
        return;
      }
      codeInjectionModule.onEval(reader);
    }
  }

  public static class RemoteEvalAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.CODE_INJECTION)
    public static void onEnter(
        @Advice.Argument(0) final String url, @Advice.Argument(1) final String text) {
      if (url == null) {
        return;
      }
      // Two schemes: "http:" (via URL.openConnection) and "bsh:" (via a raw socket); any other
      // scheme throws before any I/O. bsh.* is excluded from call-site instrumentation,
      // so neither the URL nor the socket SSRF call-site sink fires inside bsh.
      if (url.startsWith("http:") || url.startsWith("bsh:")) {
        final SsrfModule ssrfModule = InstrumentationBridge.SSRF;
        if (ssrfModule != null) {
          ssrfModule.onURLConnection(url);
        }
      }

      if (text == null) {
        return;
      }
      final CodeInjectionModule codeInjectionModule = InstrumentationBridge.CODE_INJECTION;
      if (codeInjectionModule == null) {
        return;
      }
      codeInjectionModule.onEval(text);
    }
  }
}
