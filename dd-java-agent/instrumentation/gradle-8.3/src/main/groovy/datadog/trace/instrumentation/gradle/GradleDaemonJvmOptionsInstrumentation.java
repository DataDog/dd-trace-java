package datadog.trace.instrumentation.gradle;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import net.bytebuddy.asm.Advice;
import org.gradle.process.internal.JvmOptions;

/**
 * This instrumentation targets Gradle Launcher, which is the process that is started with
 * `gradle`/`gradlew` commands. The launcher starts Gradle Daemon (if not started yet), which is a
 * long-lived process that actually runs builds. The instrumentation injects the tracer and its
 * config properties into Gradle Daemon JVM settings when the daemon is started.
 */
@AutoService(InstrumenterModule.class)
public class GradleDaemonJvmOptionsInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradleDaemonJvmOptionsInstrumentation() {
    super("gradle", "gradle-daemon-jvm-options");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.launcher.daemon.configuration.DaemonJvmOptions";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        GradleDaemonJvmOptionsInstrumentation.class.getName() + "$InjectJavaAgent");
  }

  public static class InjectJavaAgent {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void injectJavaAgent(@Advice.This final JvmOptions daemonJvmOptions) {
      File agentJar = Config.get().getCiVisibilityAgentJarFile();
      Path agentJarPath = agentJar.toPath();

      StringBuilder agentArg = new StringBuilder("-javaagent:").append(agentJarPath).append('=');

      Properties systemProperties = System.getProperties();
      for (Map.Entry<Object, Object> e : systemProperties.entrySet()) {
        String propertyName = (String) e.getKey();
        Object propertyValue = e.getValue();
        if (propertyName.startsWith(Config.PREFIX)) {
          daemonJvmOptions.systemProperty(propertyName, propertyValue);
          agentArg.append(propertyName).append('=').append(propertyValue).append(',');
        }
      }

      daemonJvmOptions.jvmArgs(agentArg);
    }
  }
}
