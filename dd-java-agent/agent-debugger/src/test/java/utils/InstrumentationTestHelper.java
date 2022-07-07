package utils;

import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.agent.CapturedSnapshotTest;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
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

  public static Map<String, byte[]> compile(String className)
      throws IOException, URISyntaxException {
    return compile(className, SourceCompiler.DebugInfo.ALL);
  }

  public static Map<String, byte[]> compile(String className, SourceCompiler.DebugInfo debugInfo)
      throws IOException, URISyntaxException {
    String classSource = getFixtureContent("/" + className.replace('.', '/') + ".java");
    return SourceCompiler.compile(className, classSource, debugInfo);
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
}
