package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.smoketest.backend.AgentBackend;
import datadog.trace.test.util.PollingConditions;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.RegisterExtension;

@EnabledForJreRange(min = JRE.JAVA_9)
class LateAttachSmokeTest {
  private static final String SSI_ATTACH_AGENT_ARGS =
      "dd.injection.enabled=tracer,dd.injection.mode=attach";

  private static final AgentBackend BACKEND = AgentBackend.mockAgent();

  @RegisterExtension
  static final SmokeServerApp app =
      SmokeServerApp.named("late-attach")
          .jar(System.getProperty("datadog.smoketest.springboot.shadowJar.path"))
          .noAgent()
          .skipScopeContinuationCheck(
              "The tracer is attached at runtime; the companion requires a -javaagent launch.")
          .backend(BACKEND)
          .placeholder("agent.port", () -> Integer.toString(BACKEND.port()))
          .jvmArgs(
              "-Ddd.trace.agent.port=${agent.port}",
              "-Ddd.service.name=" + ProcessManager.SERVICE_NAME,
              "-Ddd.env=" + ProcessManager.ENV,
              "-Ddd.version=" + ProcessManager.VERSION)
          .args("--server.port=${app.httpPort}")
          .build();

  @Test
  @DisplayName("requests are traced after the tracer is attached")
  void tracesAfterLateAttach() throws Exception {
    assertEquals(404, app.get("/hello/not-here"));
    assertEquals(0, app.traces().getTraces().size(), "no traces before the tracer is attached");

    attachTracer("--server.port=" + app.httpPort());

    new PollingConditions(30)
        .eventually(() -> {
          app.get("/hello/not-here");
          app.traces().waitForTraceCount(1, 1);
        });
  }

  private static void attachTracer(String uniqueCommandLineArg) throws Exception {
    String agentJar = System.getProperty("datadog.smoketest.agent.shadowJar.path");
    Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
    Class<?> descriptorClass = Class.forName("com.sun.tools.attach.VirtualMachineDescriptor");
    Method displayName = descriptorClass.getMethod("displayName");
    Method id = descriptorClass.getMethod("id");

    String pid = null;
    for (Object descriptor : (List<?>) vmClass.getMethod("list").invoke(null)) {
      String[] commandLine = ((String) displayName.invoke(descriptor)).split(" ");
      if (Arrays.asList(commandLine).contains(uniqueCommandLineArg)) {
        pid = (String) id.invoke(descriptor);
        break;
      }
    }
    if (pid == null) {
      throw new IllegalStateException("No JVM found with " + uniqueCommandLineArg);
    }

    Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
    try {
      vmClass
          .getMethod("loadAgent", String.class, String.class)
          .invoke(vm, agentJar, SSI_ATTACH_AGENT_ARGS);
    } finally {
      vmClass.getMethod("detach").invoke(vm);
    }
  }
}
