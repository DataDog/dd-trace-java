package datadog.trace.instrumentation.log4j2;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.config.LoggerConfig;

@AutoService(InstrumenterModule.class)
public class LoggerConfigInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public LoggerConfigInstrumentation() {
    super("log4j", "log4j-2", "logs-intake", "logs-intake-log4j-2");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isAgentlessLogSubmissionEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.apache.logging.log4j.core.config.LoggerConfig";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".DatadogAppender"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        LoggerConfigInstrumentation.class.getName() + "$LoggerConfigConstructorAdvice");
  }

  public static class LoggerConfigConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This LoggerConfig loggerConfig) {
      Map<String, Appender> appenders = loggerConfig.getAppenders();
      if (appenders != null) {
        for (Appender appender : appenders.values()) {
          if (appender instanceof DatadogAppender) {
            return;
          }
        }
      }

      DatadogAppender appender = new DatadogAppender("datadog", null);
      appender.start();

      Config config = Config.get();
      Level level = Level.valueOf(config.getAgentlessLogSubmissionLevel());
      loggerConfig.addAppender(appender, level, null);
    }
  }
}
