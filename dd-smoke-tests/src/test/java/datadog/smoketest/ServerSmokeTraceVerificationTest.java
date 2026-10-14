package datadog.smoketest;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spockframework.runtime.SpecInfoBuilder;
import org.spockframework.runtime.SpecificationContext;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.IterationInfo;
import org.spockframework.runtime.model.MethodInfo;
import org.spockframework.runtime.model.SpecInfo;

class ServerSmokeTraceVerificationTest {
  @TempDir Path directory;

  @Test
  void excludedApplicationFeaturesDoNotRequireTraces() throws Throwable {
    Fixture fixture = fixture("");
    // Filtering leaves only inherited features selected; the application feature never runs.
    SpecInfo spec = new SpecInfoBuilder(AbstractServerSmokeTest.class).build();
    for (FeatureInfo feature : spec.getFeatures()) {
      runSetup(fixture, feature);
    }
    assertDoesNotThrow(() -> cleanup(fixture));
  }

  @Test
  void cleanupWithoutExecutingAnyFeatureDoesNotRequireTraces() throws Exception {
    Fixture fixture = fixture("");
    assertDoesNotThrow(() -> cleanup(fixture));
  }

  @Test
  void applicationRunStillRequiresExpectedTraces() throws Throwable {
    Fixture fixture = fixture("");
    runSetup(fixture, applicationFeature());
    assertThrows(AssertionError.class, () -> cleanup(fixture));
  }

  @Test
  void applicationRunAcceptsExpectedTraces() throws Throwable {
    Fixture fixture = fixture("[request]\n");
    runSetup(fixture, applicationFeature());
    assertDoesNotThrow(() -> cleanup(fixture));
  }

  @Test
  void telemetryAfterApplicationDoesNotDisableTraceVerification() throws Throwable {
    Fixture fixture = fixture("");
    runSetup(fixture, applicationFeature());
    SpecInfo spec = new SpecInfoBuilder(AbstractServerSmokeTest.class).build();
    for (FeatureInfo feature : spec.getFeatures()) {
      runSetup(fixture, feature);
    }
    assertThrows(AssertionError.class, () -> cleanup(fixture));
  }

  private Fixture fixture(String traces) throws Exception {
    Fixture fixture = new Fixture();
    SpecificationContext context = (SpecificationContext) fixture.getSpecificationContext();
    context.setSharedInstance(fixture);
    File output = directory.resolve("traces.out").toFile();
    Files.write(output.toPath(), traces.getBytes(StandardCharsets.UTF_8));
    InvokerHelper.setProperty(fixture, "numberOfProcesses", 1);
    InvokerHelper.setProperty(fixture, "outputs", new File[] {output});
    return fixture;
  }

  private static FeatureInfo applicationFeature() throws Exception {
    MethodInfo method = new MethodInfo();
    method.setReflection(Fixture.class.getDeclaredMethod("request"));
    FeatureInfo feature = new FeatureInfo();
    feature.setFeatureMethod(method);
    return feature;
  }

  // Invoke the real base fixtures without launching an application or running telemetry checks.
  private static void runSetup(Fixture fixture, FeatureInfo feature) throws Throwable {
    SpecificationContext context = (SpecificationContext) fixture.getSpecificationContext();
    context.setCurrentIteration(new IterationInfo(feature, 0, new Object[0], 1));
    SpecInfo spec = new SpecInfoBuilder(AbstractServerSmokeTest.class).build();
    for (MethodInfo method : spec.getSetupMethods()) {
      method.invoke(fixture);
    }
  }

  private static void cleanup(Fixture fixture) throws Throwable {
    SpecInfo spec = new SpecInfoBuilder(AbstractServerSmokeTest.class).build();
    for (MethodInfo method : spec.getCleanupSpecMethods()) {
      method.invoke(fixture);
    }
  }

  public static class Fixture extends AbstractServerSmokeTest {
    public void request() {}

    @Override
    protected Set<String> expectedTraces() {
      return singleton("[request]");
    }
  }
}
