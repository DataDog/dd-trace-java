package datadog.trace.civisibility.coverage;

import org.jacoco.agent.rt.internal_b6258fc.Agent;
import org.jacoco.agent.rt.internal_b6258fc.CoverageTransformer;
import org.jacoco.agent.rt.internal_b6258fc.IExceptionLogger;
import org.jacoco.agent.rt.internal_b6258fc.core.runtime.AgentOptions;
import org.jacoco.agent.rt.internal_b6258fc.core.runtime.IRuntime;
import org.jacoco.agent.rt.internal_b6258fc.core.runtime.InjectedClassRuntime;
import org.jacoco.agent.rt.internal_b6258fc.core.runtime.ModifiedSystemClassRuntime;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class CoverageSubSystem {
  public static void start(Instrumentation instrumentation) throws Exception {
    AgentOptions agentOptions = new AgentOptions();
    agentOptions.setOutput(AgentOptions.OutputMode.file);
    agentOptions.setInclBootstrapClasses(false);
    agentOptions.setExcludes("datadog.**:org.jacoco.**");
    Agent agent = Agent.getInstance(agentOptions);

    IRuntime runtime = createRuntime(instrumentation);
    runtime.startup(agent.getData());
    instrumentation.addTransformer(new CoverageTransformer(runtime, agentOptions, IExceptionLogger.SYSTEM_ERR));
  }

  private static IRuntime createRuntime(final Instrumentation inst)
      throws Exception {

    if (redefineJavaBaseModule(inst)) {
      return new InjectedClassRuntime(Object.class, "$DDJaCoCo");
    }

    return ModifiedSystemClassRuntime.createFor(inst,
        "java/lang/UnknownError");
  }

  /**
   * Opens {@code java.base} module for {@link InjectedClassRuntime} when
   * executed on Java 9 JREs or higher.
   *
   * @return <code>true</code> when running on Java 9 or higher,
   *         <code>false</code> otherwise
   * @throws Exception
   *             if unable to open
   */
  private static boolean redefineJavaBaseModule(
      final Instrumentation instrumentation) throws Exception {
    try {
      Class.forName("java.lang.Module");
    } catch (final ClassNotFoundException e) {
      return false;
    }

    Instrumentation.class.getMethod("redefineModule", //
        Class.forName("java.lang.Module"), //
        Set.class, //
        Map.class, //
        Map.class, //
        Set.class, //
        Map.class //
    ).invoke(instrumentation, // instance
        getModule(Object.class), // module
        Collections.emptySet(), // extraReads
        Collections.emptyMap(), // extraExports
        Collections.singletonMap("java.lang",
            Collections.singleton(
                getModule(InjectedClassRuntime.class))), // extraOpens
        Collections.emptySet(), // extraUses
        Collections.emptyMap() // extraProvides
    );
    return true;
  }

  /**
   * @return {@code cls.getModule()}
   */
  private static Object getModule(final Class<?> cls) throws Exception {
    return Class.class //
        .getMethod("getModule") //
        .invoke(cls);
  }
}
