package datadog.trace.agent.test;

import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork;
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork;

import datadog.trace.bootstrap.BootstrapProxy;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class IntegrationTestUtils {
  /** Returns the classloader the core agent is running on. */
  public static ClassLoader getAgentClassLoader() {
    Field classloaderField = null;
    try {
      final Class<?> agentClass =
          ClassLoader.getSystemClassLoader().loadClass("datadog.trace.bootstrap.Agent");
      classloaderField = agentClass.getDeclaredField("AGENT_CLASSLOADER");
      classloaderField.setAccessible(true);
      return (ClassLoader) classloaderField.get(null);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    } finally {
      if (null != classloaderField) {
        classloaderField.setAccessible(false);
      }
    }
  }

  /** Returns the classloader to use for bootstrap resources. */
  public static ClassLoader getBootstrapProxy() {
    return BootstrapProxy.INSTANCE;
  }

  public static File createJarFileWithClasses(final Class<?>... classes) throws IOException {
    return createJarFileWithClasses(null, classes);
  }

  /** See {@link IntegrationTestUtils#createJarWithClasses(String, Class[])} */
  public static URL createJarWithClasses(final Class<?>... classes) throws IOException {
    return createJarWithClasses(null, classes);
  }

  /**
   * Create a temporary jar on the filesystem with the bytes of the given classes.
   *
   * <p>The jar file will be removed when the jvm exits.
   *
   * @param mainClassName The name of the class to use for Main-Class and Premain-Class. May be null
   * @param classes classes to package into the jar.
   * @return the location of the newly created jar.
   * @throws IOException
   */
  public static File createJarFileWithClasses(final String mainClassName, final Class<?>... classes)
      throws IOException {
    final File tmpJar = File.createTempFile(UUID.randomUUID() + "-", ".jar");
    tmpJar.deleteOnExit();

    final Manifest manifest = new Manifest();
    if (mainClassName != null) {
      final Attributes mainAttributes = manifest.getMainAttributes();
      mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
      mainAttributes.put(Attributes.Name.MAIN_CLASS, mainClassName);
      mainAttributes.put(new Attributes.Name("Premain-Class"), mainClassName);
    }

    try (final JarOutputStream target =
        new JarOutputStream(Files.newOutputStream(tmpJar.toPath()), manifest)) {
      for (final Class<?> clazz : classes) {
        addToJar(clazz, target);
      }
    }

    return tmpJar;
  }

  public static URL createJarWithClasses(final String mainClassName, final Class<?>... classes)
      throws IOException {

    return createJarFileWithClasses(mainClassName, classes).toURI().toURL();
  }

  private static void addToJar(final Class<?> clazz, final JarOutputStream jarOutputStream)
      throws IOException {
    InputStream inputStream = null;
    ClassLoader loader = clazz.getClassLoader();
    if (null == loader) {
      // bootstrap resources can be fetched through the system loader
      loader = ClassLoader.getSystemClassLoader();
    }
    try {
      final JarEntry entry = new JarEntry(getResourceName(clazz.getName()));
      jarOutputStream.putNextEntry(entry);
      inputStream = loader.getResourceAsStream(getResourceName(clazz.getName()));

      final byte[] buffer = new byte[1024];
      while (true) {
        final int count = inputStream.read(buffer);
        if (count == -1) {
          break;
        }
        jarOutputStream.write(buffer, 0, count);
      }
      jarOutputStream.closeEntry();
    } finally {
      if (inputStream != null) {
        inputStream.close();
      }
    }
  }

  /** com.foo.Bar -> com/foo/Bar.class */
  public static String getResourceName(final String className) {
    return className.replace('.', '/') + ".class";
  }

  public static String[] getBootstrapPackagePrefixes() throws Exception {
    final Field f =
        getAgentClassLoader()
            .loadClass("datadog.trace.bootstrap.Constants")
            .getField("BOOTSTRAP_PACKAGE_PREFIXES");
    return (String[]) f.get(null);
  }

  private static String getAgentArgument() {
    final RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
    for (final String arg : runtimeMxBean.getInputArguments()) {
      if (arg.startsWith("-javaagent")) {
        return arg;
      }
    }

    throw new RuntimeException("Agent jar not found");
  }

  private static String getAgentJarLocation() {
    return getAgentArgument().replace("-javaagent:", "");
  }

  public static int runOnSeparateJvm(
      final String mainClassName,
      final String[] jvmArgs,
      final String[] mainMethodArgs,
      final Map<String, String> envVars,
      final boolean printOutputStreams)
      throws Exception {
    return runOnSeparateJvm(
        mainClassName, jvmArgs, mainMethodArgs, envVars, printOutputStreams ? System.out : null);
  }

  public static int runOnSeparateJvm(
      final String mainClassName,
      final String[] jvmArgs,
      final String[] mainMethodArgs,
      final Map<String, String> envVars,
      final PrintStream out)
      throws Exception {
    final String classPath = System.getProperty("java.class.path");
    return runOnSeparateJvm(mainClassName, jvmArgs, mainMethodArgs, envVars, classPath, out);
  }

  public static int runOnSeparateJvm(
      final String mainClassName,
      final String[] jvmArgs,
      final String[] mainMethodArgs,
      final Map<String, String> envVars,
      final File classpath,
      final boolean printOutputStreams)
      throws Exception {
    return runOnSeparateJvm(
        mainClassName, jvmArgs, mainMethodArgs, envVars, classpath.getPath(), printOutputStreams);
  }

  public static int runOnSeparateJvm(
      final String mainClassName,
      final String[] jvmArgs,
      final String[] mainMethodArgs,
      final Map<String, String> envVars,
      final String classpath,
      final boolean printOutputStreams)
      throws Exception {
    return runOnSeparateJvm(
        mainClassName,
        jvmArgs,
        mainMethodArgs,
        envVars,
        classpath,
        printOutputStreams ? System.out : null);
  }

  /**
   * On a separate JVM, run the main method for a given class.
   *
   * @param mainClassName The name of the entry point class. Must declare a main method.
   * @param out Optional stream to print the stdout and stderr of the child jvm
   * @return the return code of the child jvm
   * @throws Exception
   */
  public static int runOnSeparateJvm(
      final String mainClassName,
      final String[] jvmArgs,
      final String[] mainMethodArgs,
      final Map<String, String> envVars,
      final String classpath,
      final PrintStream out)
      throws Exception {
    final Process process =
        startOnSeparateJvm(mainClassName, jvmArgs, mainMethodArgs, envVars, classpath);

    final StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "ERROR", out);
    final StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "OUTPUT", out);
    outputGobbler.start();
    errorGobbler.start();

    waitFor(process, 30, TimeUnit.SECONDS);

    outputGobbler.join();
    errorGobbler.join();

    return process.exitValue();
  }

  public static Process startOnSeparateJvm(
      final String mainClassName,
      final String[] jvmArgs,
      final String[] mainMethodArgs,
      final Map<String, String> envVars,
      final String classpath)
      throws Exception {
    final String separator = System.getProperty("file.separator");
    final String path = System.getProperty("java.home") + separator + "bin" + separator + "java";

    final List<String> vmArgsList = new ArrayList<>(Arrays.asList(jvmArgs));

    boolean runAsJar = "datadog.trace.bootstrap.AgentJar".equals(mainClassName);

    if (!runAsJar) {
      vmArgsList.add(getAgentArgument());
    }
    vmArgsList.add(getMaxMemoryArgumentForFork());
    vmArgsList.add(getMinMemoryArgumentForFork());
    vmArgsList.add("-XX:ErrorFile=/tmp/hs_err_pid%p.log");

    final List<String> commands = new ArrayList<>();
    commands.add(path);
    commands.addAll(vmArgsList);
    if (runAsJar) {
      commands.add("-jar");
      commands.add(getAgentJarLocation());
    } else {
      commands.add("-cp");
      commands.add(classpath);
      commands.add(mainClassName);
    }
    commands.addAll(Arrays.asList(mainMethodArgs));
    final ProcessBuilder processBuilder = new ProcessBuilder(commands.toArray(new String[0]));
    processBuilder.environment().putAll(envVars);

    return processBuilder.start();
  }

  private static void waitFor(final Process process, final long timeout, final TimeUnit unit)
      throws InterruptedException, TimeoutException {
    final long startTime = System.nanoTime();
    long rem = unit.toNanos(timeout);

    do {
      try {
        process.exitValue();
        return;
      } catch (final IllegalThreadStateException ex) {
        if (rem > 0) {
          Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
        }
      }
      rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
    } while (rem > 0);
    throw new TimeoutException();
  }

  private static class StreamGobbler extends Thread {
    InputStream stream;
    String type;
    PrintStream out;

    private StreamGobbler(final InputStream stream, final String type, final PrintStream out) {
      this.stream = stream;
      this.type = type;
      this.out = out;
    }

    @Override
    public void run() {
      try {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        while ((line = reader.readLine()) != null) {
          if (null != out) {
            out.println(type + "> " + line);
          }
        }
      } catch (final IOException e) {
        e.printStackTrace();
      }
    }
  }
}
