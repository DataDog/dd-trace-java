package datadog.trace.agent.tooling.advice;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.concreteClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonList;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.muzzle.MuzzleGenerationProcessor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/** Build-time plugin that scans each module once and runs ordered scan consumers. */
public class AdviceScanningGradlePlugin extends Plugin.ForElementMatcher {
  static {
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
  }

  private final File targetDirectory;
  private final List<AdviceProcessor<?>> processors;

  public AdviceScanningGradlePlugin(File targetDirectory) {
    this(targetDirectory, singletonList(new MuzzleGenerationProcessor()));
  }

  AdviceScanningGradlePlugin(File targetDirectory, List<AdviceProcessor<?>> processors) {
    super(concreteClass().and(extendsClass(named(InstrumenterModule.class.getName()))));
    this.targetDirectory = targetDirectory;
    this.processors = processors;
  }

  @Override
  public DynamicType.Builder<?> apply(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassFileLocator classFileLocator) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    InstrumenterModule module;
    try {
      // The module instance is shared by scanning and every processor.
      module =
          (InstrumenterModule)
              loader.loadClass(typeDescription.getName()).getConstructor().newInstance();
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException(
          "Cannot instantiate instrumenter module " + typeDescription.getName(), error);
    }

    AdviceScanResult scanResult = AdviceScanner.scan(module);
    AdviceProcessorContext context = new AdviceProcessorContext(module, targetDirectory);
    for (AdviceProcessor<?> processor : processors) {
      runProcessor(processor, scanResult, context);
    }
    return builder;
  }

  private static <T> void runProcessor(
      AdviceProcessor<T> processor, AdviceScanResult scanResult, AdviceProcessorContext context) {
    T result = processor.process(scanResult, context);
    context.putResult(processor.resultType(), result);
  }

  @Override
  public void close() throws IOException {}
}
