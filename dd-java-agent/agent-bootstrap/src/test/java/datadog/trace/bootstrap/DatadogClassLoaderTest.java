package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DatadogClassLoaderTest {
  private static final URL testJarLocation =
      toUrl(new File("src/test/resources/classloader-test-jar/testjar-jdk8"));
  private static final URL nestedTestJarLocation =
      toUrl(new File("src/test/resources/classloader-test-jar/jar-with-nested-classes-jdk8"));

  private static URL toUrl(File file) {
    try {
      return file.toURI().toURL();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ClassLoader.getClassLoadingLock is protected; reach it via reflection, matching what the
  // original Groovy test did implicitly.
  private static Object classLoadingLock(ClassLoader cl, String name) throws Exception {
    Method m = ClassLoader.class.getDeclaredMethod("getClassLoadingLock", String.class);
    m.setAccessible(true);
    return m.invoke(cl, name);
  }

  @Test
  @Timeout(60)
  void agentClassloaderDoesNotLockClassloadingAroundInstance() throws Exception {
    // setup
    String className1 = "some/class/Name1";
    String className2 = "some/class/Name2";
    DatadogClassLoader ddLoader = new DatadogClassLoader();
    Object lock1 = classLoadingLock(ddLoader, className1);
    Object lock2 = classLoadingLock(ddLoader, className2);
    Phaser threadHoldLockPhase = new Phaser(2);
    Phaser acquireLockFromMainThreadPhase = new Phaser(2);

    // when
    Thread thread1 =
        new Thread() {
          @Override
          public void run() {
            synchronized (lock1) {
              threadHoldLockPhase.arrive();
              acquireLockFromMainThreadPhase.arriveAndAwaitAdvance();
            }
          }
        };
    thread1.start();

    Thread thread2 =
        new Thread() {
          @Override
          public void run() {
            threadHoldLockPhase.arriveAndAwaitAdvance();
            synchronized (lock2) {
              acquireLockFromMainThreadPhase.arrive();
            }
          }
        };
    thread2.start();
    thread1.join();
    thread2.join();

    // then — reaching this point means no deadlock occurred
  }

  @Test
  void agentClassloaderSuccessfullyLoadsClassesConcurrently() throws Exception {
    // given
    DatadogClassLoader ddLoader = new DatadogClassLoader(testJarLocation, null);

    // when
    ExecutorService executorService = Executors.newCachedThreadPool();
    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      futures.add(
          executorService.submit(
              () -> {
                ddLoader.loadClass("a.A");
                return null;
              }));
    }
    for (Future<Void> future : futures) {
      try {
        future.get();
      } catch (Exception ex) {
        if (ex.getCause() instanceof Throwable) {
          throw (Exception) ex.getCause();
        }
        throw ex;
      }
    }

    // then — no exception thrown
  }

  @Test
  void loadNestedClassesAndCallGetEnclosingClass() throws Exception {
    // given
    DatadogClassLoader ddLoader = new DatadogClassLoader(nestedTestJarLocation, null);

    // when
    Class<?> klass = ddLoader.loadClass("p.EnclosingClass$StaticInnerClass");

    // then
    assertEquals("StaticInnerClass", klass.getSimpleName());

    // when
    Class<?> enclosing = klass.getEnclosingClass();

    // then
    assertEquals("EnclosingClass", enclosing.getSimpleName());
  }

  /**
   * Regression test for APMS-19624 / DataDog/dd-trace-java#6398. The resource URL must be built
   * from the agent jar URL (a properly-formed {@code file:} URL), not from {@code
   * JarFile.getName()} (an OS-native path). On Windows the OS-native form is {@code
   * C:\Datadog\dd-java-agent.jar}, which produces the malformed URL {@code
   * jar:file:C:\Datadog\dd-java-agent.jar!/...} and breaks helper-class injection on Spring Boot
   * 1.3.5 {@code LaunchedURLClassLoader}.
   *
   * <p>We exercise the divergence cross-platform by copying the test jar into a directory whose
   * name contains a space: {@code URL.toString()} percent-encodes it, {@code JarFile.getName()}
   * does not.
   */
  @Test
  void findResourceUsesAgentJarUrlAsPrefix(@org.junit.jupiter.api.io.TempDir File tempDir)
      throws Exception {
    File spacedDir = new File(tempDir, "dir with spaces");
    assertTrue(spacedDir.mkdirs());
    File spacedJar = new File(spacedDir, "testjar-jdk8");
    Files.copy(
        new File("src/test/resources/classloader-test-jar/testjar-jdk8").toPath(),
        spacedJar.toPath());

    URL spacedJarUrl = spacedJar.toURI().toURL();
    DatadogClassLoader ddLoader = new DatadogClassLoader(spacedJarUrl, null);

    URL resource = ddLoader.findResource("a/A.class");
    assertNotNull(resource, "findResource should locate a/A.class in the test jar");

    String expectedPrefix = "jar:" + spacedJarUrl + "!/";
    assertTrue(
        resource.toString().startsWith(expectedPrefix),
        () ->
            "resource URL ("
                + resource
                + ") should start with the agent jar URL prefix ("
                + expectedPrefix
                + ") — pre-fix code derives the prefix from JarFile.getName(),"
                + " which leaves the space unencoded and on Windows produces a malformed URL.");
  }
}
