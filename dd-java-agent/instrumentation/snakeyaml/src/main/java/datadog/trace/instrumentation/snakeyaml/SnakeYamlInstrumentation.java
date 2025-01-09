package datadog.trace.instrumentation.snakeyaml;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.UntrustedDeserializationModule;
import java.io.InputStream;
import java.io.Reader;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;
import org.yaml.snakeyaml.constructor.Constructor;

@AutoService(InstrumenterModule.class)
public class SnakeYamlInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SnakeYamlInstrumentation() {
    super("snakeyaml", "snakeyaml");
  }

  @Override
  public String muzzleDirective() {
    return "snakeyaml-1.x";
  }

  static final ElementMatcher.Junction<ClassLoader> NOT_SNAKEYAML_2 =
      not(hasClassNamed("org.yaml.snakeyaml.inspector.TagInspector"));

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return NOT_SNAKEYAML_2;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SnakeYamlHelper",
    };
  }

  @Override
  public String instrumentedType() {
    return "org.yaml.snakeyaml.Yaml";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("load")
            .and(isMethod())
            .and(
                takesArguments(String.class)
                    .or(takesArguments(InputStream.class))
                    .or(takesArguments(Reader.class))),
        SnakeYamlInstrumentation.class.getName() + "$LoadAdvice");
  }

  public static class LoadAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNTRUSTED_DESERIALIZATION)
    public static void onEnter(
        @Advice.Argument(0) final Object data, @Advice.This final Yaml self) {
      if (data == null) {
        return;
      }
      final UntrustedDeserializationModule untrustedDeserialization =
          InstrumentationBridge.UNTRUSTED_DESERIALIZATION;
      if (untrustedDeserialization == null) {
        return;
      }
      final BaseConstructor constructor = SnakeYamlHelper.fetchConstructor(self);
      // For versions prior to 1.7 (not included), the constructor field is null
      if (constructor instanceof Constructor || constructor == null) {
        untrustedDeserialization.onObject(data);
      }
    }
  }
}
