package datadog.trace.instrumentation.weaver;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sbt.testing.TaskDef;
import weaver.framework.SuiteEvent;

@AutoService(InstrumenterModule.class)
public class WeaverInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public WeaverInstrumentation() {
    super("ci-visibility", "weaver");
  }

  @Override
  public String instrumentedType() {
    return "weaver.framework.SbtTask";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WeaverUtils",
      packageName + ".DatadogWeaverReporter",
      packageName + ".TaskDefAwareLinkedBlockingQueueProxy",
      packageName + ".TaskDefAwareConcurrentLinkedQueueProxy",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), WeaverInstrumentation.class.getName() + "$SbtTaskCreationAdvice");
  }

  public static class SbtTaskCreationAdvice {
    // TODO: JEP 500 - avoid mutating final fields
    @SuppressForbidden
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onTaskCreation(
        @Advice.This Object sbtTask, @Advice.FieldValue("taskDef") TaskDef taskDef) {
      try {
        Field queueField = sbtTask.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        boolean isFinal = Modifier.isFinal(queueField.getModifiers());
        if (isFinal) {
          try {
            Logger log = LoggerFactory.getLogger(SbtTaskCreationAdvice.class);
            log.warn(
                "JEP 500: Final field 'queue' in class '{}' is being mutated. This will soon be disallowed. Field type: {}. Field declaring class: {}.",
                sbtTask.getClass().getName(),
                queueField.getType().getName(),
                queueField.getDeclaringClass().getName());
          } catch (Throwable logError) {
            // Ignore logging errors to avoid failing instrumentation
          }
        }
        Object queue = queueField.get(sbtTask);
        if (queue instanceof ConcurrentLinkedQueue) {
          // disney's implementation (0.8.4+) uses a ConcurrentLinkedQueue for the field
          queueField.set(
              sbtTask,
              new TaskDefAwareConcurrentLinkedQueueProxy<SuiteEvent>(
                  taskDef, (ConcurrentLinkedQueue<SuiteEvent>) queue));
        } else if (queue instanceof LinkedBlockingQueue) {
          // typelevel's implementation (0.9+) uses a LinkedBlockingQueue for the field
          queueField.set(
              sbtTask,
              new TaskDefAwareLinkedBlockingQueueProxy<SuiteEvent>(
                  taskDef, (LinkedBlockingQueue<SuiteEvent>) queue));
        }
      } catch (Exception ignored) {
      }
    }
  }
}
