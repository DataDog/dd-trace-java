package datadog.trace.bootstrap;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point for initializing the agent.
 *
 * <p>The bootstrap process of the agent is somewhat complicated and care has to be taken to make
 * sure things do not get broken by accident.
 *
 * <p>JVM loads this class onto app's classloader, afterwards agent needs to inject its classes onto
 * bootstrap classpath. This leads to this class being visible on bootstrap. This in turn means that
 * this class may be loaded again on bootstrap by accident if we ever reference it after bootstrap
 * has been setup.
 *
 * <p>In order to avoid this we need to make sure we do a few things:
 *
 * <ul>
 *   <li>Do as little as possible here
 *   <li>Never reference this class after we have setup bootstrap and jumped over to 'real' agent
 *       code
 *   <li>Do not store any static data in this class
 *   <li>Do dot touch any logging facilities here so we can configure them later
 * </ul>
 */
public final class AgentBootstrap {
  private static final Class<?> thisClass = AgentBootstrap.class;
  private static final int MAX_EXCEPTION_CHAIN_LENGTH = 99;

  private static boolean initialized = false;

  public static void premain(final String agentArgs, final Instrumentation inst) {
    agentmain(agentArgs, inst);
  }

  public static void agentmain(final String agentArgs, final Instrumentation inst) {
    if (alreadyInitialized() || lessThanJava8() || isJdkTool()) {
      return;
    }

    try {
      final URL agentJarURL = installAgentJar(inst);
      final Class<?> agentClass = Class.forName("datadog.trace.bootstrap.Agent", true, null);
      if (agentClass.getClassLoader() != null) {
        throw new IllegalStateException("DD Java Agent NOT added to bootstrap classpath.");
      }
      final Method startMethod =
          agentClass.getMethod("start", Instrumentation.class, URL.class, String.class);
      startMethod.invoke(null, inst, agentJarURL, agentArgs);
    } catch (final Throwable ex) {
      if (exceptionCauseChainContains(
          ex, "datadog.trace.util.throwable.FatalAgentMisconfigurationError")) {
        throw new Error(ex);
      }
      // Don't rethrow.  We don't have a log manager here, so just print.
      System.err.println("ERROR " + thisClass.getName());
      ex.printStackTrace();
    }
  }

  static boolean exceptionCauseChainContains(Throwable ex, String exClassName) {
    Set<Throwable> stack = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    Throwable t = ex;
    while (t != null && stack.add(t) && stack.size() <= MAX_EXCEPTION_CHAIN_LENGTH) {
      // cannot do an instanceof check since most of the agent's code is loaded by an isolated CL
      if (t.getClass().getName().equals(exClassName)) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }

  private static boolean lessThanJava8() {
    return lessThanJava8(System.getProperty("java.version"), System.out);
  }

  // Reachable for testing
  static boolean lessThanJava8(String version, PrintStream output) {
    if (parseJavaMajorVersion(version) < 8) {
      String agentVersion = "This version"; // If we can't find the agent version
      try {
        agentVersion = AgentJar.getAgentVersion();
        agentVersion = "Version " + agentVersion;
      } catch (IOException ignored) {
      }
      output.println(
          "Warning: "
              + agentVersion
              + " of dd-java-agent is not compatible with Java "
              + version
              + " and will not be installed.");
      output.println(
          "Please upgrade your Java version to 8+ or use the 0.x version of dd-java-agent in your build tool or download it from https://dtdg.co/java-tracer-v0");
      return true;
    }
    return false;
  }

  private static boolean alreadyInitialized() {
    if (initialized) {
      System.out.println(
          "Warning: dd-java-agent is being initialized more than once. Please, check that you are defining -javaagent:dd-java-agent.jar only once.");
      return true;
    }
    initialized = true;
    return false;
  }

  private static boolean isJdkTool() {
    return System.getProperty("jdk.module.main", "").startsWith("jdk.");
  }

  // Reachable for testing
  static int parseJavaMajorVersion(String version) {
    int major = 0;
    if (null == version || version.isEmpty()) {
      return major;
    }
    int start = 0;
    if (version.charAt(0) == '1'
        && version.length() >= 3
        && version.charAt(1) == '.'
        && Character.isDigit(version.charAt(2))) {
      start = 2;
    }
    // Parse the major digit and be a bit lenient, allowing digits followed by any non digit
    for (int i = start; i < version.length(); i++) {
      char c = version.charAt(i);
      if (Character.isDigit(c)) {
        major *= 10;
        major += Character.digit(c, 10);
      } else {
        break;
      }
    }
    return major;
  }

  public static void main(final String[] args) {
    if (lessThanJava8()) {
      return;
    }
    AgentJar.main(args);
  }

  private static synchronized URL installAgentJar(final Instrumentation inst)
      throws IOException, URISyntaxException {
    URL ddJavaAgentJarURL = null;

    // First try Code Source
    final CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();

    if (codeSource != null) {
      ddJavaAgentJarURL = codeSource.getLocation();
      if (ddJavaAgentJarURL != null) {
        final File ddJavaAgentJarPath = new File(ddJavaAgentJarURL.toURI());

        if (!ddJavaAgentJarPath.isDirectory()) {
          checkJarManifestMainClassIsThis(ddJavaAgentJarURL);
          inst.appendToBootstrapClassLoaderSearch(new JarFile(ddJavaAgentJarPath));
          return ddJavaAgentJarURL;
        }
      }
    }

    System.out.println("Could not get bootstrap jar from code source, using -javaagent arg");

    // ManagementFactory indirectly references java.util.logging.LogManager
    // - On Oracle-based JDKs after 1.8
    // - On IBM-based JDKs since at least 1.7
    // This prevents custom log managers from working correctly
    // Use reflection to bypass the loading of the class
    final List<String> arguments = getVMArgumentsThroughReflection();

    String agentArgument = null;
    for (final String arg : arguments) {
      if (arg.startsWith("-javaagent")) {
        if (agentArgument == null) {
          agentArgument = arg;
        } else {
          throw new IllegalStateException(
              "Multiple javaagents specified and code source unavailable, not installing tracing agent");
        }
      }
    }

    if (agentArgument == null) {
      throw new IllegalStateException(
          "Could not find javaagent parameter and code source unavailable, not installing tracing agent");
    }

    // argument is of the form -javaagent:/path/to/dd-java-agent.jar=optionalargumentstring
    final Matcher matcher = Pattern.compile("-javaagent:([^=]+).*").matcher(agentArgument);

    if (!matcher.matches()) {
      throw new IllegalStateException("Unable to parse javaagent parameter: " + agentArgument);
    }

    final File javaagentFile = new File(matcher.group(1));
    if (!(javaagentFile.exists() || javaagentFile.isFile())) {
      throw new IllegalStateException("Unable to find javaagent file: " + javaagentFile);
    }
    ddJavaAgentJarURL = javaagentFile.toURI().toURL();
    checkJarManifestMainClassIsThis(ddJavaAgentJarURL);
    inst.appendToBootstrapClassLoaderSearch(new JarFile(javaagentFile));

    return ddJavaAgentJarURL;
  }

  @SuppressForbidden
  private static List<String> getVMArgumentsThroughReflection() {
    try {
      // Try Oracle-based
      // IBM Semeru Runtime 1.8.0_345-b01 will throw UnsatisfiedLinkError here.
      final Class<?> managementFactoryHelperClass =
          Class.forName("sun.management.ManagementFactoryHelper");

      final Class<?> vmManagementClass = Class.forName("sun.management.VMManagement");

      Object vmManagement;

      try {
        vmManagement =
            managementFactoryHelperClass.getDeclaredMethod("getVMManagement").invoke(null);
      } catch (final NoSuchMethodException e) {
        // Older vm before getVMManagement() existed
        final Field field = managementFactoryHelperClass.getDeclaredField("jvm");
        field.setAccessible(true);
        vmManagement = field.get(null);
        field.setAccessible(false);
      }

      return (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);

    } catch (final ReflectiveOperationException | UnsatisfiedLinkError e) {
      try { // Try IBM-based.
        final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
        final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
        return Arrays.asList(argArray);
      } catch (final ReflectiveOperationException e1) {
        // Fallback to default
        System.out.println(
            "WARNING: Unable to get VM args through reflection.  A custom java.util.logging.LogManager may not work correctly");

        return ManagementFactory.getRuntimeMXBean().getInputArguments();
      }
    }
  }

  private static boolean checkJarManifestMainClassIsThis(final URL jarUrl) throws IOException {
    final URL manifestUrl = new URL("jar:" + jarUrl + "!/META-INF/MANIFEST.MF");
    final String mainClassLine = "Main-Class: " + thisClass.getCanonicalName();
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(manifestUrl.openStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.equals(mainClassLine)) {
          return true;
        }
      }
    }
    throw new IllegalStateException(
        "dd-java-agent is not installed, because class '"
            + thisClass.getCanonicalName()
            + "' is located in '"
            + jarUrl
            + "'. Make sure you don't have this .class-file anywhere, besides dd-java-agent.jar");
  }
}
