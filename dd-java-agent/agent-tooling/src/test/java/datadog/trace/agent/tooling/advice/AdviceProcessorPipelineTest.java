package datadog.trace.agent.tooling.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.PipelineModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.ScanModule;
import datadog.trace.agent.tooling.muzzle.MuzzleGenerationProcessor;
import datadog.trace.agent.tooling.muzzle.MuzzleGenerationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
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
    DynamicType.Builder<?> transformed =
        plugin.apply(
            new ByteBuddy().redefine(PipelineModule.class),
            description,
            ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()));

    byte[] generatedModule = transformed.make().getBytes();

    assertTrue(generatedModule.length > 0);
    assertEquals(1, PipelineModule.instances);
    assertEquals(1, ScanModule.adviceRegistrations);
    assertNotNull(third.scanResult);
    assertTrue(Files.isRegularFile(third.muzzle.getGeneratedClass().toPath()));
    assertTrue(
        Stream.of(third.muzzle.getReferences())
            .anyMatch(reference -> reference.className.equals("extra.AddedReference")));
    assertTrue(
        Stream.of(third.muzzle.getReferences())
            .noneMatch(
                reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassReader")));
    assertTrue(
        Stream.of(third.muzzle.getReferences())
            .noneMatch(
                reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassWriter")));
  }

  private static final class CapturingProcessor implements AdviceProcessor<String> {
    private AdviceScanResult scanResult;
    private MuzzleGenerationResult muzzle;

    @Override
    public Class<String> resultType() {
      return String.class;
    }

    @Override
    public String process(AdviceScanResult scanResult, AdviceProcessorContext context) {
      this.scanResult = scanResult;
      this.muzzle = context.getResult(MuzzleGenerationResult.class);
      return "captured";
    }
  }
}
