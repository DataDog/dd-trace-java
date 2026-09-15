package datadog.trace.agent.tooling.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.PipelineModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.ScanModule;
import datadog.trace.agent.tooling.muzzle.MuzzleGenerationProcessor;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdviceProcessorPipelineTest {
  @Test
  void oneScanFeedsMuzzleAndThirdProcessor(@TempDir Path temp) throws Exception {
    PipelineModule.instances = 0;
    ScanModule.adviceRegistrations = 0;
    CapturingProcessor third = new CapturingProcessor();
    AdviceScanningGradlePlugin plugin =
        new AdviceScanningGradlePlugin(
            temp.toFile(), Arrays.asList(new MuzzleGenerationProcessor(), third));
    TypeDescription description = new TypeDescription.ForLoadedType(PipelineModule.class);
    plugin.apply(
        new ByteBuddy().redefine(PipelineModule.class),
        description,
        ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()));
    Path muzzleClass =
        temp.resolve(PipelineModule.class.getName().replace('.', '/') + "$Muzzle.class");

    assertEquals(1, PipelineModule.instances);
    assertEquals(1, ScanModule.adviceRegistrations);
    assertNotNull(third.scanResult);
    assertTrue(Files.isRegularFile(muzzleClass));
    assertTrue(
        Stream.of(third.muzzle)
            .anyMatch(reference -> reference.className.equals("extra.AddedReference")));
    assertTrue(
        Stream.of(third.muzzle)
            .noneMatch(
                reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassReader")));
    assertTrue(
        Stream.of(third.muzzle)
            .noneMatch(
                reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassWriter")));
  }

  private static final class CapturingProcessor implements AdviceProcessor<String> {
    private AdviceScanResult scanResult;
    private Reference[] muzzle;

    @Override
    public Class<String> resultType() {
      return String.class;
    }

    @Override
    public String process(AdviceScanResult scanResult, AdviceProcessorContext context) {
      this.scanResult = scanResult;
      this.muzzle = context.getResult(Reference[].class);
      return "captured";
    }
  }
}
