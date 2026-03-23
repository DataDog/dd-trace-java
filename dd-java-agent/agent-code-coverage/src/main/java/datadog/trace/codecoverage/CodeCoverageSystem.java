package datadog.trace.codecoverage;

import datadog.trace.api.Config;
import java.lang.instrument.Instrumentation;
import java.util.function.Predicate;

/**
 * Entry point for the production code coverage product module.
 *
 * <p>Follows the tracer's standard product system pattern with a two-phase start:
 *
 * <ol>
 *   <li>{@link #start(Instrumentation)} — called during premain, <b>before</b> ByteBuddy's
 *       transformer is registered. Must not use logging, NIO, or JMX.
 *   <li>{@link #startCollector(Object)} — called from a deferred callback after premain, when
 *       logging and thread scheduling are safe.
 * </ol>
 */
public final class CodeCoverageSystem {

  /**
   * Phase 1: registers the coverage {@link java.lang.instrument.ClassFileTransformer}.
   *
   * <p>Called during premain, synchronously, before ByteBuddy. The returned object is an opaque
   * handle to the transformer, passed to {@link #startCollector(Object)} later.
   *
   * @param inst the JVM instrumentation service
   * @return the transformer instance (opaque; passed to {@link #startCollector})
   * @throws Exception if JaCoCo runtime initialization fails
   */
  public static Object start(Instrumentation inst) throws Exception {
    Config config = Config.get();
    String[] includes = config.getCodeCoverageIncludes();
    String[] excludes = config.getCodeCoverageExcludes();
    Predicate<String> filter = new CodeCoverageFilter(includes, excludes);
    CodeCoverageTransformer transformer = new CodeCoverageTransformer(inst, filter);
    inst.addTransformer(transformer);
    return transformer;
  }

  /**
   * Phase 2: starts the periodic coverage collector.
   *
   * <p>Called from a deferred callback after premain. Safe to use logging and thread scheduling.
   *
   * @param transformerObj the opaque transformer handle returned by {@link #start}
   */
  public static void startCollector(Object transformerObj) {
    CodeCoverageTransformer transformer = (CodeCoverageTransformer) transformerObj;
    Config config = Config.get();
    CodeCoverageSender sender = new LoggingCodeCoverageSender();
    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            transformer,
            sender,
            config.getCodeCoverageReportIntervalSeconds(),
            config.getCodeCoverageClasspath());
    collector.start();
  }

  private CodeCoverageSystem() {}
}
