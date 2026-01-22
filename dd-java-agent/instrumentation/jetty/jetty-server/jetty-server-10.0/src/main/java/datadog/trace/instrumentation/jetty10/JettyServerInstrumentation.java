package datadog.trace.instrumentation.jetty10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.instrumentation.jetty9.HttpChannelHandleVisitor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

@AutoService(InstrumenterModule.class)
public final class JettyServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
    Instrumenter.HasTypeAdvice,
    Instrumenter.HasMethodAdvice,
    ExcludeFilterProvider {

  private boolean appSecNotFullyDisabled;

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".ExtractAdapter",
        packageName + ".ExtractAdapter$Request",
        packageName + ".ExtractAdapter$Response",
        packageName + ".JettyDecorator",
        packageName + ".JettyDecorator$OnResponse",
        "datadog.trace.instrumentation.jetty9.RequestURIDataAdapter",
        "datadog.trace.instrumentation.jetty.JettyBlockResponseFunction",
        "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
    };
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    this.appSecNotFullyDisabled = enabledSystems.contains(TargetSystem.APPSEC);
    return super.isApplicable(enabledSystems);
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[]{
        new Reference.Builder("org.eclipse.jetty.server.HttpChannel")
            .withMethod(new String[0], Reference.EXPECTS_NON_STATIC, "handle", "Z")
            .withMethod(new String[0], Reference.EXPECTS_NON_STATIC, "recycle", "V")
            .withMethod(
                new String[0],
                Reference.EXPECTS_NON_STATIC,
                "handleException",
                "V",
                "Ljava/lang/Throwable;")
            .build()
    };
  }

  @Override
  public String muzzleDirective() {
    return "10_series";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new HttpChannelHandleVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(takesNoArguments().and(named("handle")),
        packageName + ".ContextTrackingAdvice",
        packageName + ".HandleAdvice");
    transformer.applyAdvice(named("recycle").and(takesNoArguments()), packageName + ".ResetAdvice");

    if (appSecNotFullyDisabled) {
      transformer.applyAdvice(
          named("handleException").and(takesArguments(1)).and(takesArgument(0, Throwable.class)),
          packageName + ".HandleExceptionAdvice");
    }
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Arrays.asList(
            "org.eclipse.jetty.util.thread.strategy.ProduceConsume",
            "org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume",
            "org.eclipse.jetty.io.ManagedSelector",
            "org.eclipse.jetty.util.thread.TimerScheduler",
            "org.eclipse.jetty.util.thread.TimerScheduler$SimpleTask"));
  }

  public static class HttpChannelHandleVisitorWrapper implements AsmVisitorWrapper {

    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      if (Config.get().getAppSecActivation() == ProductActivation.FULLY_DISABLED) {
        return classVisitor;
      }

      return new HttpChannelHandleVisitor(Opcodes.ASM7, classVisitor);
    }
  }
}
