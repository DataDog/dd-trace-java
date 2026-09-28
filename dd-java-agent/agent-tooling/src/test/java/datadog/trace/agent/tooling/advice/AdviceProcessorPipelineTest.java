package datadog.trace.agent.tooling.advice;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.HelperGenerationProcessor;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.CatchModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.HierarchyModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.NestedHelperModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.PipelineModule;
import datadog.trace.agent.tooling.advice.AdviceScanningFixtures.ScanModule;
import datadog.trace.agent.tooling.advice.AdviceScanningHelper.Dependency;
import datadog.trace.agent.tooling.muzzle.MuzzleGenerationProcessor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.instrumentation.testing.AdviceHierarchy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
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
    DynamicType.Builder<?> transformed =
        plugin.apply(
            new ByteBuddy().redefine(PipelineModule.class),
            description,
            ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()));
    String muzzleClassName = PipelineModule.class.getName() + "$Muzzle";
    Path muzzleClass = temp.resolve(muzzleClassName.replace('.', '/') + ".class");

    assertEquals(1, PipelineModule.instances);
    assertEquals(1, ScanModule.adviceRegistrations);
    assertNotNull(additional.scanResult);
    assertArrayEquals(
        new String[] {
          AdviceScanningHelper.class.getName(),
          Dependency.class.getName(),
          AdviceScanningHelper.anonymousClass().getName(),
          AdviceScanningHelper.localClass().getName()
        },
        additional.helpers);
    Class<?> generatedModule =
        transformed
            .make()
            .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded();
    assertArrayEquals(
        additional.helpers,
        (String[])
            generatedModule
                .getMethod("helperClassNames")
                .invoke(generatedModule.getConstructor().newInstance()));
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
    assertTrue(
        Stream.of(muzzle)
            .noneMatch(reference -> asList(additional.helpers).contains(reference.className)));
  }

  @Test
  void retainsAdviceHierarchyMuzzleChecksWithoutInjectingHierarchy(@TempDir Path temp)
      throws Exception {
    HierarchyModule module = new HierarchyModule();
    AdviceScanResult scan = AdviceScanner.scan(module);
    String[] helpers = new HelperGenerationProcessor().resolveHelpers(scan, module);

    assertArrayEquals(new String[0], helpers);
    for (Class<?> type :
        new Class<?>[] {AdviceHierarchy.Superclass.class, AdviceHierarchy.Interface.class}) {
      assertTrue(scan.getClassInfo(type.getName()).isFromModuleOutput());
      assertTrue(scan.getClassInfo(type.getName()).isScanned());
      assertFalse(scan.getClassInfo(type.getName()).isReachableFromAdvice());
    }

    new MuzzleGenerationProcessor()
        .process(scan, new AdviceProcessorContext(module, temp.toFile(), helpers));
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {temp.toUri().toURL()}, getClass().getClassLoader())) {
      ReferenceMatcher matcher =
          (ReferenceMatcher)
              loader
                  .loadClass(HierarchyModule.class.getName() + "$Muzzle")
                  .getMethod("create")
                  .invoke(null);
      assertTrue(
          Stream.of(matcher.getReferences())
              .filter(reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassReader"))
              .flatMap(reference -> Stream.of(reference.methods))
              .anyMatch(
                  method -> method.name.equals("getAccess") && method.methodType.equals("()I")));
      assertTrue(
          Stream.of(matcher.getReferences())
              .filter(reference -> reference.className.equals("net.bytebuddy.jar.asm.ClassWriter"))
              .flatMap(reference -> Stream.of(reference.methods))
              .anyMatch(
                  method ->
                      method.name.equals("newUTF8")
                          && method.methodType.equals("(Ljava/lang/String;)I")));
    }
  }

  @Test
  void excludesMuzzleHelpersReferencedOnlyByMethodBodiesOrInterfaces() {
    ScanModule module = new ScanModule();
    AdviceScanResult scan = AdviceScanner.scan(module);
    String[] helpers = new HelperGenerationProcessor().resolveHelpers(scan, module);

    for (Class<?> muzzleHelper :
        new Class<?>[] {
          AdviceScanningHelper.MuzzleHelper.class,
          AdviceScanningHelper.MuzzleReferenceProvider.class
        }) {
      assertTrue(scan.getClassInfo(muzzleHelper.getName()).isScanned());
      assertTrue(scan.getClassInfo(muzzleHelper.getName()).isReachableFromAdvice());
      assertFalse(asList(helpers).contains(muzzleHelper.getName()));
    }
    assertTrue(asList(helpers).contains(Dependency.class.getName()));
  }

  @Test
  void generatesCatchOnlyHelpersThatLoadInIsolation(@TempDir Path temp) throws Exception {
    ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
    Class<?> generatedModule =
        new AdviceScanningGradlePlugin(temp.toFile())
            .apply(
                new ByteBuddy().redefine(CatchModule.class),
                new TypeDescription.ForLoadedType(CatchModule.class),
                locator)
            .make()
            .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded();
    String[] helpers =
        ((InstrumenterModule) generatedModule.getConstructor().newInstance()).helperClassNames();
    Map<String, byte[]> helperClasses = new LinkedHashMap<>();
    for (String helper : helpers) {
      helperClasses.put(helper, locator.locate(helper).resolve());
    }

    ClassLoader isolated = new ByteArrayClassLoader(null, helperClasses);
    Class<?> helper = isolated.loadClass(CatchOnlyHelper.class.getName());
    assertSame(isolated, helper.getClassLoader());
    helper.getMethod("run").invoke(null);
    assertTrue(asList(helpers).contains(CatchOnlyException.class.getName()));
  }

  @Test
  void nestedHelperLoadsWithoutItsEnclosingModule() throws Exception {
    NestedHelperModule module = new NestedHelperModule();
    AdviceScanResult scan = AdviceScanner.scan(module);
    String[] helpers = new HelperGenerationProcessor().resolveHelpers(scan, module);

    assertArrayEquals(new String[] {NestedHelperModule.Helper.class.getName()}, helpers);
    assertFalse(scan.getClassInfo(NestedHelperModule.class.getName()).isReachableFromAdvice());
    ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
    Map<String, byte[]> helperClasses = new LinkedHashMap<>();
    for (String helper : helpers) {
      helperClasses.put(helper, locator.locate(helper).resolve());
    }

    ClassLoader isolated = new ByteArrayClassLoader(null, helperClasses);
    Class<?> helper = isolated.loadClass(NestedHelperModule.Helper.class.getName());
    assertSame(isolated, helper.getClassLoader());
    assertEquals("nested helper", helper.getMethod("run").invoke(null));
  }

  @Test
  void excludesEnumerationOnlyNestedClassesAndTheirDependencies() {
    ScanModule module = new ScanModule();
    AdviceScanResult scan = AdviceScanner.scan(module);
    String[] helpers = new HelperGenerationProcessor().resolveHelpers(scan, module);

    for (Class<?> unused :
        new Class<?>[] {
          AdviceScanningHelper.DiagnosticPrinter.class,
          AdviceScanningHelper.DiagnosticDependency.class
        }) {
      assertTrue(scan.getClassInfo(unused.getName()).isScanned());
      assertFalse(scan.getClassInfo(unused.getName()).isReachableFromAdvice());
      assertFalse(asList(helpers).contains(unused.getName()));
    }
    assertTrue(asList(helpers).contains(Dependency.class.getName()));
  }

  @Test
  void manualHelperListIsNotMerged() {
    class ManualModule extends ScanModule {
      @Override
      public String[] helperClassNames() {
        return new String[] {"manual.Helper"};
      }
    }
    ManualModule module = new ManualModule();
    AdviceScanResult scanResult = AdviceScanner.scan(module);
    String[] helpers = new HelperGenerationProcessor().resolveHelpers(scanResult, module);

    assertArrayEquals(new String[] {"manual.Helper"}, helpers);
  }

  private static final class CapturingProcessor implements AdviceProcessor {
    private AdviceScanResult scanResult;
    private String[] helpers;

    @Override
    public void process(AdviceScanResult scanResult, AdviceProcessorContext context) {
      this.scanResult = scanResult;
      this.helpers = context.getHelperClassNames();
    }
  }
}
