package datadog.trace.bootstrap;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/** Additional agent checks that require Java 11+. */
public final class AdvancedAgentChecks {

  /** Returns {@code true} if the JVM is writing to a CDS/AOT archive, i.e. is in training mode. */
  @SuppressForbidden
  public static boolean isAotTraining(Instrumentation inst) {
    try {
      Class<?> cds = Class.forName("jdk.internal.misc.CDS");

      // ensure the module containing CDS exports it to our unnamed module
      Module cdsModule = cds.getModule();
      Module unnamedModule = AdvancedAgentChecks.class.getClassLoader().getUnnamedModule();
      inst.redefineModule(
          cdsModule,
          emptySet(),
          singletonMap("jdk.internal.misc", singleton(unnamedModule)),
          emptyMap(),
          emptySet(),
          emptyMap());

      // if the JVM is writing to a CDS/AOT archive then it's in training mode
      Method isDumpingArchive = cds.getMethod("isDumpingArchive");
      return (boolean) isDumpingArchive.invoke(null);
    } catch (Throwable ignore) {
      return false; // if we don't have access then assume we're not training
    }
  }
}
