package com.datadog.appsec.sca;

import static com.datadog.appsec.sca.ScaBytecodeTestUtils.bytecodeOf;
import static com.datadog.appsec.sca.ScaBytecodeTestUtils.loadModified;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.CveSnapshot;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.DependencySnapshot;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests verifying that ASM bytecode injection works on real library classes from the
 * three libraries added to validate the new database format.
 *
 * <p>Two complementary levels of coverage:
 *
 * <ol>
 *   <li><b>Injection tests</b> - load real bytecode, inject the callback, verify the bytecode grew
 *       (proving the named method exists in the class and the callback was inserted).
 *   <li><b>Callback test</b> - for junrar, where the constructor is accessible with reflection,
 *       also calls the instrumented method and verifies the callback fires even when the method
 *       body throws NPE due to a null argument.
 * </ol>
 */
class ScaRealLibraryBytecodeTest {

  @BeforeEach
  void setUp() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
    ScaReachabilityCallback.register(
        (vulnId, artifact, version, dotClassName, methodName, line) ->
            ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
                artifact, version, vulnId, dotClassName, methodName, line));
  }

  @AfterEach
  void tearDown() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
    ScaReachabilityCallback.register(null);
  }

  // ---------------------------------------------------------------------------
  // Injection tests: ASM can process real library bytecode
  // ---------------------------------------------------------------------------

  @Test
  void junrar_createDirectory_injectionProducesBytecodeChange() throws Exception {
    Class<?> localFolderExtractor = Class.forName("com.github.junrar.LocalFolderExtractor");
    byte[] original = bytecodeOf(localFolderExtractor);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "createDirectory",
        Collections.singletonList(
            spec(
                "GHSA-hf5p-q87m-crj7",
                "com.github.junrar:junrar",
                "7.5.5",
                "com.github.junrar.LocalFolderExtractor",
                "createDirectory")));

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);

    assertNotNull(modified, "ASM injection must not return null on real junrar bytecode");
    assertTrue(
        modified.length > original.length,
        "injected bytecode must be larger than original; proves createDirectory exists"
            + " and the callback was inserted");
  }

  @Test
  void zserio_array_read_injectionProducesBytecodeChange() throws Exception {
    byte[] original = bytecodeOf(zserio.runtime.array.Array.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "read",
        Collections.singletonList(
            spec(
                "GHSA-cwq5-8pvq-j65j",
                "io.github.ndsev:zserio-runtime",
                "2.16.1",
                "zserio.runtime.array.Array",
                "read")));

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);

    assertNotNull(modified);
    assertTrue(
        modified.length > original.length,
        "injected bytecode must be larger than original; proves Array.read exists"
            + " and the callback was inserted");
  }

  @Test
  void tomcat_chunkedInputFilter_parseChunkHeader_injectionProducesBytecodeChange()
      throws Exception {
    byte[] original = bytecodeOf(org.apache.coyote.http11.filters.ChunkedInputFilter.class);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "parseChunkHeader",
        Collections.singletonList(
            spec(
                "GHSA-563x-q5rq-57qp",
                "org.apache.tomcat.embed:tomcat-embed-core",
                "9.0.115",
                "org.apache.coyote.http11.filters.ChunkedInputFilter",
                "parseChunkHeader")));

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);

    assertNotNull(modified);
    assertTrue(
        modified.length > original.length,
        "injected bytecode must be larger; proves parseChunkHeader exists and was instrumented");
  }

  // ---------------------------------------------------------------------------
  // Callback test: the injected callback fires on a real method call
  // ---------------------------------------------------------------------------

  @Test
  void junrar_createDirectory_callbackFiresWhenMethodCalled() throws Exception {
    Class<?> localFolderExtractor = Class.forName("com.github.junrar.LocalFolderExtractor");
    byte[] original = bytecodeOf(localFolderExtractor);
    Map<String, List<ScaMethodCallbackInjector.MethodCallbackSpec>> callbacks = new HashMap<>();
    callbacks.put(
        "createDirectory",
        Collections.singletonList(
            spec(
                "GHSA-hf5p-q87m-crj7",
                "com.github.junrar:junrar",
                "7.5.5",
                "com.github.junrar.LocalFolderExtractor",
                "createDirectory")));

    byte[] modified = ScaMethodCallbackInjector.inject(original, callbacks);
    Class<?> cls = loadModified(modified);

    // Constructor is package-private in junrar; setAccessible(true) bypasses visibility.
    Constructor<?> ctor = cls.getDeclaredConstructor(File.class);
    ctor.setAccessible(true);
    Object instance = ctor.newInstance(new File("/tmp"));

    // createDirectory(FileHeader) is package-private. Passing null triggers the injected callback
    // at method entry before the body tries to access header fields (NPE expected after callback).
    Class<?> fileHeaderClass = Class.forName("com.github.junrar.rarfile.FileHeader");
    Method m = cls.getDeclaredMethod("createDirectory", fileHeaderClass);
    m.setAccessible(true);
    try {
      m.invoke(instance, (Object) null);
    } catch (InvocationTargetException ignored) {
      // NPE from null FileHeader is expected; the callback already fired before it
    }

    List<ScaReachabilityHit> hits = drainHits();
    assertEquals(
        1, hits.size(), "createDirectory callback must fire even when method body throws NPE");
    ScaReachabilityHit hit = hits.get(0);
    assertEquals("GHSA-hf5p-q87m-crj7", hit.vulnId());
    assertEquals("com.github.junrar:junrar", hit.artifact());
    assertEquals("7.5.5", hit.version());
  }

  // ---------------------------------------------------------------------------
  // Helpers (duplicated from ScaReachabilityMethodLevelTest to keep tests self-contained)
  // ---------------------------------------------------------------------------

  private static List<ScaReachabilityHit> drainHits() {
    List<ScaReachabilityHit> result = new ArrayList<>();
    for (DependencySnapshot dep :
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies()) {
      for (CveSnapshot cve : dep.cves) {
        if (cve.hit != null) {
          result.add(cve.hit);
        }
      }
    }
    return result;
  }

  private static ScaMethodCallbackInjector.MethodCallbackSpec spec(
      String vulnId, String artifact, String version, String dotClass, String method) {
    return new ScaMethodCallbackInjector.MethodCallbackSpec(
        vulnId, artifact, version, dotClass, method);
  }
}
