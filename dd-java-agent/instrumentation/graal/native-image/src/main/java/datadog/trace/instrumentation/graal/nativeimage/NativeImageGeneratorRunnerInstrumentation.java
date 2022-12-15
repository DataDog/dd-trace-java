package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class NativeImageGeneratorRunnerInstrumentation
    extends AbstractNativeImageInstrumentation implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.oracle.svm.hosted.NativeImageGeneratorRunner";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("extractDriverArguments")),
        NativeImageGeneratorRunnerInstrumentation.class.getName() + "$MainAdvice");
  }

  public static class MainAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return List<String> args) {
      args.add("-H:+AddAllCharsets");
      args.add("-H:EnableURLProtocols=http");
      args.add(
          "-H:ReflectionConfigurationResources="
              + "META-INF/native-image/com.datadoghq/dd-java-agent/reflect-config.json");
      args.add(
          "-H:ClassInitialization="
              + "datadog.trace.api.Platform:rerun,"
              + "datadog.trace.api.env.CapturedEnvironment:build_time,"
              + "datadog.trace.api.ConfigDefaults:build_time,"
              + "datadog.trace.api.InstrumenterConfig:build_time,"
              + "datadog.trace.api.Functions:build_time,"
              + "datadog.trace.api.GlobalTracer:build_time,"
              + "datadog.trace.api.WithGlobalTracer:build_time,"
              + "datadog.trace.bootstrap.config.provider.ConfigConverter:build_time,"
              + "datadog.trace.bootstrap.config.provider.ConfigProvider:build_time,"
              + "datadog.trace.bootstrap.Agent:build_time,"
              + "datadog.trace.bootstrap.BootstrapProxy:build_time,"
              + "datadog.trace.bootstrap.CallDepthThreadLocalMap:build_time,"
              + "datadog.trace.bootstrap.DatadogClassLoader:build_time,"
              + "datadog.trace.bootstrap.FieldBackedContextStores:build_time,"
              + "datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState:build_time,"
              + "datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter:build_time,"
              + "datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper:build_time,"
              + "datadog.trace.logging.LoggingSettingsDescription:build_time,"
              + "datadog.trace.logging.simplelogger.SLCompatFactory:build_time,"
              + "datadog.trace.util.CollectionUtils:build_time,"
              + "datadog.slf4j.impl.StaticLoggerBinder:build_time,"
              + "datadog.slf4j.LoggerFactory:build_time,"
              + "com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap:build_time,"
              + "net.bytebuddy:build_time,"
              + "com.sun.proxy:build_time");
    }
  }
}
