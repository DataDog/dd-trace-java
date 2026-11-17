package utils;

import static utils.TestHelper.getFixtureContent;
import static utils.TestHelper.getFixtureLines;

import com.datadog.debugger.agent.CapturedSnapshotTest;
import datadog.instrument.classinject.ClassInjector;
import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InstrumentationTestHelper {
  public static Class<?> loadClass(String className, Map<String, byte[]> classFileBuffers) {
    ClassLoader classLoader =
        new MemClassLoader(CapturedSnapshotTest.class.getClassLoader(), classFileBuffers);
    try {
      return classLoader.loadClass(className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static Class<?> compileAndLoadClass(String className)
      throws IOException, URISyntaxException {
    Map<String, byte[]> classFileBuffers = compile(className);
    return loadClass(className, classFileBuffers);
  }

  public static Class<?> compileAndLoadClass(String className, String version)
      throws IOException, URISyntaxException {
    Map<String, byte[]> classFileBuffers = compile(className, version);
    return loadClass(className, classFileBuffers);
  }

  public static Map<String, byte[]> compile(String className)
      throws IOException, URISyntaxException {
    return compile(className, SourceCompiler.DebugInfo.ALL, "8");
  }

  public static Map<String, byte[]> compile(String className, String version)
      throws IOException, URISyntaxException {
    return compile(className, SourceCompiler.DebugInfo.ALL, version);
  }

  public static Map<String, byte[]> compile(
      String className, SourceCompiler.DebugInfo debugInfo, String version)
      throws IOException, URISyntaxException {
    return compile(className, debugInfo, version, Collections.emptyList());
  }

  public static Map<String, byte[]> compile(
      String className,
      SourceCompiler.DebugInfo debugInfo,
      String version,
      List<String> additionalOptions)
      throws IOException, URISyntaxException {
    String classSource = getFixtureContent("/" + className.replace('.', '/') + ".java");
    return SourceCompiler.compile(className, classSource, debugInfo, version, additionalOptions);
  }

  public static Class<?> loadClass(String className, String classFileName) throws IOException {
    Map<String, byte[]> classFileBuffers = new HashMap<>();
    byte[] buffer = Files.readAllBytes(Paths.get(classFileName));
    classFileBuffers.put(className, buffer);
    return loadClass(className, classFileBuffers);
  }

  public static Class<?> loadClassFromJar(String className, String jarFileName)
      throws ClassNotFoundException, MalformedURLException {
    URLClassLoader jarClassLoader =
        new URLClassLoader(new URL[] {new URL("file://" + jarFileName)});
    return jarClassLoader.loadClass(className);
  }

  public static int getLineForLineProbe(String className, ProbeId lineProbeId) {
    return getLineForLineProbe(className, ".java", lineProbeId);
  }

  /**
   * Installs the real tracer instrumentation modules (e.g. jax-rs-annotations) on top of the given
   * {@link Instrumentation}, so that classes defined afterwards (typically via {@link
   * #compileAndLoadClass}) are woven with actual tracer advice, the same way they would be by the
   * production agent. Callers must remove the returned transformer once done, e.g. via {@link
   * Instrumentation#removeTransformer(ClassFileTransformer)}.
   */
  public static ClassFileTransformer installTracerInstrumentation(Instrumentation instr) {
    ClassInjector.enableClassInjection(instr);
    return AgentInstaller.installBytebuddyAgent(
        instr, false, EnumSet.of(InstrumenterModule.TargetSystem.TRACING));
  }

  public static int getLineForLineProbe(String className, String ext, ProbeId lineProbeId) {
    List<String> lines = getFixtureLines("/" + className.replace('.', '/') + ext);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.contains("//") && line.contains(lineProbeId.getId())) {
        return i + 1;
      }
    }
    return -1;
  }
}
