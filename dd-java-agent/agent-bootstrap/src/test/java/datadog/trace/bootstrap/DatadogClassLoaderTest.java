package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
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

  /**
   * Regression test for APPSEC-69906. Deployments that upgrade the agent replace its jar on disk
   * while the JVM keeps running. Class loading survives that, because it reads through the {@link
   * java.util.jar.JarFile} handle opened at construction time, but resource loading used to go
   * through a {@code jar:} URL that can no longer be opened — and {@link
   * ClassLoader#getResourceAsStream} turns the resulting {@link java.io.IOException} into a silent
   * {@code null}. AppSec surfaced that as a bogus "Resource default_config.json not found".
   */
  @Test
  void getResourceAsStreamSurvivesAgentJarRemoval(@org.junit.jupiter.api.io.TempDir File tempDir)
      throws Exception {
    // deleting a file that is still open is rejected on Windows, so the scenario cannot arise there
    assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));

    File jar = new File(tempDir, "testjar-jdk8");
    Files.copy(
        new File("src/test/resources/classloader-test-jar/testjar-jdk8").toPath(), jar.toPath());
    DatadogClassLoader ddLoader = new DatadogClassLoader(jar.toURI().toURL(), null);

    // the jar goes away from under the running JVM; the handle opened above still refers to it
    Files.delete(jar.toPath());

    // the URL is still resolved, but is now unreadable — this is what used to yield a silent null
    URL resource = ddLoader.findResource("a/A.class");
    assertNotNull(resource, "findResource should still resolve a/A.class from the jar index");
    assertThrows(IOException.class, resource::openStream);

    assertArrayEquals(
        originalEntryBytes(),
        readFully(ddLoader, "a/A.class"),
        "getResourceAsStream should read through the retained jar handle");
  }

  /**
   * Companion to {@link #getResourceAsStreamSurvivesAgentJarRemoval}, covering what an agent
   * upgrade actually does: the pathname is replaced by a <em>readable</em> jar of a different
   * build. Opening the {@code jar:} URL would then succeed and hand back the new jar's copy of the
   * resource, while classes keep coming from the retained handle — mixing two builds. Resources
   * must come from the same jar the classes do.
   */
  @Test
  void getResourceAsStreamIgnoresAJarThatReplacedTheAgentJar(
      @org.junit.jupiter.api.io.TempDir File tempDir) throws Exception {
    // replacing a file that is still open is rejected on Windows, so the scenario cannot arise
    assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));

    File jar = new File(tempDir, "testjar-jdk8");
    Files.copy(
        new File("src/test/resources/classloader-test-jar/testjar-jdk8").toPath(), jar.toPath());
    DatadogClassLoader ddLoader = new DatadogClassLoader(jar.toURI().toURL(), null);

    // an upgrade swaps in a different build holding a different copy of the same entry
    byte[] replacementContents = "a different build of a/A.class".getBytes(StandardCharsets.UTF_8);
    File replacement = new File(tempDir, "replacement");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(replacement.toPath()))) {
      out.putNextEntry(new JarEntry("parent/a/A.classdata"));
      out.write(replacementContents);
      out.closeEntry();
    }
    // REPLACE_EXISTING alone: combining it with ATOMIC_MOVE is not portable, and the move must
    // swap the pathname rather than write through it, so the handle keeps seeing the old jar
    Files.move(replacement.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);

    assertArrayEquals(
        originalEntryBytes(),
        readFully(ddLoader, "a/A.class"),
        "getResourceAsStream should serve the jar the loader was built on, not its replacement");
  }

  private static byte[] originalEntryBytes() throws Exception {
    try (JarFile jarFile =
        new JarFile(new File("src/test/resources/classloader-test-jar/testjar-jdk8"))) {
      try (InputStream is = jarFile.getInputStream(new JarEntry("parent/a/A.classdata"))) {
        return readAllBytes(is);
      }
    }
  }

  private static byte[] readFully(ClassLoader loader, String name) throws Exception {
    try (InputStream is = loader.getResourceAsStream(name)) {
      assertNotNull(is, () -> "getResourceAsStream should have found " + name);
      return readAllBytes(is);
    }
  }

  private static byte[] readAllBytes(InputStream is) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int read;
    while ((read = is.read(buf)) != -1) {
      out.write(buf, 0, read);
    }
    return out.toByteArray();
  }
}
