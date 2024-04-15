package com.datadog.debugger.agent;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.probe.OriginProbe;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.Arrays;
import org.joor.Reflect;

public class SpanOriginTest extends CapturedSnapshotTest {
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final String SERVICE_NAME = "origin-service-name";

  //  @Test
  public void duplicateAMethod() throws IOException, URISyntaxException {
    final String CLASS_NAME = "MethodDuplication";
    OriginProbe probe =
        createOriginProbe(PROBE_ID1, CLASS_NAME, "fibonacci", "(int)", "fibonacci", "(int,int)");
    TestSnapshotListener listener = installProbes(CLASS_NAME, probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect reflect = Reflect.onClass(testClass);
    try {
      Method method = testClass.getDeclaredMethod("fibonacci_copy", int.class);
    } catch (NoSuchMethodException e) {
      fail("should have found copied method");
    }
    int result = reflect.call("main", "4").get();
    System.out.println("result = " + result);
    Object invocations = reflect.get("invocations");
    assertNotEquals(invocations, 0);
  }

  protected TestSnapshotListener installProbes(String expectedClassName, OriginProbe... probes) {
    return installProbes(
        expectedClassName,
        Configuration.builder().setService(SERVICE_NAME).add(Arrays.asList(probes)).build());
  }

  private static OriginProbe createOriginProbe(
      ProbeId id,
      String typeName,
      String dupName,
      String dupSig,
      String rewriteName,
      String rewriteSig) {
    return createProbeBuilder(id, typeName, dupName, dupSig, rewriteName, rewriteSig).build();
  }

  private static OriginProbe.Builder createProbeBuilder(
      ProbeId id,
      String typeName,
      String dupName,
      String dupSig,
      String rewriteName,
      String rewriteSig,
      String... lines) {
    return OriginProbe.builder()
        .probeId(id)
        .duplicate(dupName, dupSig)
        .rewrite(rewriteName, rewriteSig);
  }
}
