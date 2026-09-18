package datadog.trace.agent.tooling.advice;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.PipelineModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.ScanModule;
import datadog.trace.agent.tooling.muzzle.MuzzleGenerationProcessor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdviceProcessorPipelineTest {
  @Test
  void oneScanFeedsMuzzleAndAdditionalProcessor(@TempDir Path temp) throws Exception {
    PipelineModule.instances = 0;
    ScanModule.adviceRegistrations = 0;
    CapturingProcessor additional = new CapturingProcessor();
    AdviceScanningGradlePlugin plugin =
        new AdviceScanningGradlePlugin(
            temp.toFile(), asList(new MuzzleGenerationProcessor(), additional));
    TypeDescription description = new TypeDescription.ForLoadedType(PipelineModule.class);
    plugin.apply(
        new ByteBuddy().redefine(PipelineModule.class),
        description,
        ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()));
    String muzzleClassName = PipelineModule.class.getName() + "$Muzzle";
    Path muzzleClass = temp.resolve(muzzleClassName.replace('.', '/') + ".class");

    assertEquals(1, PipelineModule.instances);
    assertEquals(1, ScanModule.adviceRegistrations);
    assertNotNull(additional.scanResult);
    assertTrue(Files.isRegularFile(muzzleClass));

    Reference[] muzzle;
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {temp.toUri().toURL()}, getClass().getClassLoader())) {
      ReferenceMatcher matcher =
          (ReferenceMatcher) loader.loadClass(muzzleClassName).getMethod("create").invoke(null);
      muzzle = matcher.getReferences();
    }
    assertTrue(
        Stream.of(muzzle)
            .anyMatch(reference -> reference.className.equals("extra.AddedReference")));
    assertTrue(
        Stream.of(muzzle)
            .noneMatch(
                reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassReader")));
    assertTrue(
        Stream.of(muzzle)
            .noneMatch(
                reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassWriter")));
  }

  private static final class CapturingProcessor implements AdviceProcessor {
    private AdviceScanResult scanResult;

    @Override
    public void process(AdviceScanResult scanResult, AdviceProcessorContext context) {
      this.scanResult = scanResult;
    }
  }
}
