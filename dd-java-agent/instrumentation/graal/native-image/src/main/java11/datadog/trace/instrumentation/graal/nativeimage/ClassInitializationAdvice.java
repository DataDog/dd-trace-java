package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.hosted.classinitialization.ClassInitializationSupport;
import net.bytebuddy.asm.Advice;

public class ClassInitializationAdvice {
  @Advice.OnMethodEnter
  public static void onEnter(
      @Advice.Argument(0) ClassInitializationSupport classInitializationSupport) {
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.api.env.CapturedEnvironment", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.api.ConfigDefaults", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.api.Functions", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.api.InstrumenterConfig", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.api.Platform", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.api.GlobalTracer", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.config.provider.ConfigConverter", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.config.provider.ConfigProvider", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.bootstrap.Agent", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.bootstrap.BootstrapProxy", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.CallDepthThreadLocalMap", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.DatadogClassLoader", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.FieldBackedContextStores", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.logging.LoggingSettingsDescription", "");
    classInitializationSupport.initializeAtBuildTime(
        "datadog.trace.logging.simplelogger.SLCompatFactory", "");
    classInitializationSupport.initializeAtBuildTime("datadog.trace.util.CollectionUtils", "");
    classInitializationSupport.initializeAtBuildTime("datadog.slf4j.impl.StaticLoggerBinder", "");
    classInitializationSupport.initializeAtBuildTime("datadog.slf4j.LoggerFactory", "");
    classInitializationSupport.initializeAtBuildTime(
        "com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap", "");
    classInitializationSupport.initializeAtBuildTime("net.bytebuddy.", "");
    classInitializationSupport.initializeAtBuildTime("com.sun.proxy.", "");
  }
}
