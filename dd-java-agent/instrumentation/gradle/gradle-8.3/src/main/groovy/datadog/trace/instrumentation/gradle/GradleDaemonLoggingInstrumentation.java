package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class GradleDaemonLoggingInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GradleDaemonLoggingInstrumentation() {
    super("gradle", "gradle-daemon-logging");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.launcher.daemon.bootstrap.DaemonMain";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("initialiseLogging"),
        GradleDaemonLoggingInstrumentation.class.getName() + "$ReinitialiseLogging");
  }

  /**
   * Gradle Daemon closes original {@link System.out} and {@link System.err} streams (see {@link
   * org.gradle.launcher.daemon.bootstrap.DaemonMain#daemonStarted}), and replaces them with Gradle
   * Daemon log streams (see {@link
   * org.gradle.launcher.daemon.bootstrap.DaemonMain#initialiseLogging}).
   *
   * <p>{@link datadog.trace.logging.simplelogger.SLCompatFactory} that contains logging settings
   * gets initialized before Gradle Daemon, so DD logging settings refer to the original streams
   * that the daemon closes. They need to refer to the new streams created by the daemon, so here we
   * reset the settings letting them re-initialize to capture the new streams.
   */
  public static class ReinitialiseLogging {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void reinitialiseTracerLogging() {
      GlobalLogLevelSwitcher.get().reinitialize();
    }
  }
}
