package datadog.trace.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.environment.EnvironmentVariables;
import datadog.trace.bootstrap.environment.JavaVirtualMachine;
import datadog.trace.bootstrap.environment.SystemProperties;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

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
  static final String LIB_INJECTION_ENABLED_ENV_VAR = "DD_INJECTION_ENABLED";
  static final String LIB_INJECTION_FORCE_SYS_PROP = "dd.inject.force";
  static final String LIB_INSTRUMENTATION_SOURCE_SYS_PROP = "dd.instrumentation.source";

  private static final Class<?> thisClass = AgentBootstrap.class;
  private static final int MAX_EXCEPTION_CHAIN_LENGTH = 99;
  private static final String JAVA_AGENT_ARGUMENT = "-javaagent:";

  private static boolean initialized = false;
  private static List<File> agentFiles = null;

  public static void premain(final String agentArgs, final Instrumentation inst) {
    agentmain(agentArgs, inst);
  }

  @SuppressForbidden
  public static void agentmain(final String agentArgs, final Instrumentation inst) {
    BootstrapInitializationTelemetry initTelemetry;

    try {
      initTelemetry = createInitializationTelemetry();
    } catch (Throwable t) {
      initTelemetry = BootstrapInitializationTelemetry.noOpInstance();
    }
    try {
      agentmainImpl(initTelemetry, agentArgs, inst);
    } catch (final Throwable ex) {
      initTelemetry.onFatalError(ex);

      if (exceptionCauseChainContains(
          ex, "datadog.trace.util.throwable.FatalAgentMisconfigurationError")) {
        throw new Error(ex);
      }
      // Don't rethrow.  We don't have a log manager here, so just print.
      System.err.println("ERROR " + thisClass.getName());
      ex.printStackTrace();
    } finally {
      try {
        initTelemetry.finish();
      } catch (Throwable t) {
        // safeguard - ignore
      }
    }
  }

  private static BootstrapInitializationTelemetry createInitializationTelemetry() {
    String forwarderPath = EnvironmentVariables.get("DD_TELEMETRY_FORWARDER_PATH");
    if (forwarderPath == null) {
      return BootstrapInitializationTelemetry.noOpInstance();
    }

    BootstrapInitializationTelemetry initTelemetry =
        BootstrapInitializationTelemetry.createFromForwarderPath(forwarderPath);
    initTelemetry.initMetaInfo("runtime_name", "jvm");
    initTelemetry.initMetaInfo("language_name", "jvm");

    String javaVersion = SystemProperties.get("java.version");
    if (javaVersion != null) {
      initTelemetry.initMetaInfo("runtime_version", javaVersion);
      initTelemetry.initMetaInfo("language_version", javaVersion);
    }

    // If version was compiled into a class, then we wouldn't have the potential to be missing
    // version info
    String agentVersion = AgentJar.tryGetAgentVersion();
    if (agentVersion != null) {
      initTelemetry.initMetaInfo("tracer_version", agentVersion);
    }

    return initTelemetry;
  }

  private static void agentmainImpl(
      final BootstrapInitializationTelemetry initTelemetry,
      final String agentArgs,
      final Instrumentation inst)
      throws IOException, URISyntaxException, ReflectiveOperationException {
    if (alreadyInitialized()) {
      initTelemetry.onError("already_initialized");
      // since tracer is presumably initialized elsewhere, still considering this complete
      return;
    }
    if (isJdkTool()) {
      initTelemetry.onAbort("jdk_tool");
      return;
    }
    if (shouldAbortDueToOtherJavaAgents()) {
      initTelemetry.onAbort("other-java-agents");
      return;
    }

    if (getConfig(LIB_INJECTION_ENABLED_ENV_VAR)) {
      recordInstrumentationSource("ssi");
    } else {
      recordInstrumentationSource("cmd_line");
    }

    String agentClassName;
    if ("aot_training".equalsIgnoreCase(agentArgs)) {
      agentClassName = "datadog.trace.bootstrap.aot.TrainingAgent";
    } else {
      agentClassName = "datadog.trace.bootstrap.Agent";
    }

    final URL agentJarURL = installAgentJar(inst);
    final Class<?> agentClass;
    try {
      agentClass = Class.forName(agentClassName, true, null);
    } catch (ClassNotFoundException | LinkageError e) {
      throw new IllegalStateException("Unable to load DD Java Agent.", e);
    }
    if (agentClass.getClassLoader() != null) {
      throw new IllegalStateException("DD Java Agent NOT added to bootstrap classpath.");
    }
    try {
      final Method startMethod =
          agentClass.getMethod(
              "start", Object.class, Instrumentation.class, URL.class, String.class);
      startMethod.invoke(null, initTelemetry, inst, agentJarURL, agentArgs);
    } catch (Throwable e) {
      throw new IllegalStateException("Unable to start DD Java Agent.", e);
    }
  }

  static boolean getConfig(String configName) {
    switch (configName) {
      case LIB_INJECTION_ENABLED_ENV_VAR:
        return EnvironmentVariables.get(LIB_INJECTION_ENABLED_ENV_VAR) != null;
      case LIB_INJECTION_FORCE_SYS_PROP:
        {
          String envVarName =
              LIB_INJECTION_FORCE_SYS_PROP.replace('.', '_').replace('-', '_').toUpperCase();
          String injectionForceFlag = EnvironmentVariables.get(envVarName);
          if (injectionForceFlag == null) {
            injectionForceFlag = SystemProperties.get(LIB_INJECTION_FORCE_SYS_PROP);
          }
          return "true".equalsIgnoreCase(injectionForceFlag) || "1".equals(injectionForceFlag);
        }
      default:
        return false;
    }
  }

  private static void recordInstrumentationSource(String source) {
    SystemProperties.set(LIB_INSTRUMENTATION_SOURCE_SYS_PROP, source);
  }

  static boolean exceptionCauseChainContains(Throwable ex, String exClassName) {
    Set<Throwable> stack = Collections.newSetFromMap(new IdentityHashMap<>());
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

  @SuppressForbidden
  private static boolean alreadyInitialized() {
    if (initialized) {
      System.err.println(
          "Warning: dd-java-agent is being initialized more than once. Please check that you are defining -javaagent:dd-java-agent.jar only once.");
      return true;
    }
    initialized = true;
    return false;
  }

  private static boolean isJdkTool() {
    String moduleMain = SystemProperties.get("jdk.module.main");
    if (null != moduleMain && !moduleMain.isEmpty() && moduleMain.charAt(0) == 'j') {
      switch (moduleMain) {
        case "java.base": // keytool
        case "java.corba":
        case "java.desktop":
        case "java.rmi":
        case "java.scripting":
        case "java.security.jgss":
        case "jdk.aot":
        case "jdk.compiler":
        case "jdk.dev":
        case "jdk.hotspot.agent":
        case "jdk.httpserver":
        case "jdk.jartool":
        case "jdk.javadoc":
        case "jdk.jcmd":
        case "jdk.jconsole":
        case "jdk.jdeps":
        case "jdk.jdi":
        case "jdk.jfr":
        case "jdk.jlink":
        case "jdk.jpackage":
        case "jdk.jshell":
        case "jdk.jstatd":
        case "jdk.jvmstat.rmi":
        case "jdk.pack":
        case "jdk.pack200":
        case "jdk.policytool":
        case "jdk.rmic":
        case "jdk.scripting.nashorn.shell":
        case "jdk.xml.bind":
        case "jdk.xml.ws":
          return true;
      }
    }
    return false;
  }

  @SuppressForbidden
  static boolean shouldAbortDueToOtherJavaAgents() {
    // We don't abort if either
    // * We are not using SSI
    // * Injection is forced
    // * There is only one agent
    if (!getConfig(LIB_INJECTION_ENABLED_ENV_VAR)
        || getConfig(LIB_INJECTION_FORCE_SYS_PROP)
        || getAgentFilesFromVMArguments().size() <= 1) {
      return false;
    }

    // If there are 2 agents and one of them is for patching log4j, it's fine
    if (getAgentFilesFromVMArguments().size() == 2) {
      for (File agentFile : getAgentFilesFromVMArguments()) {
        if (agentFile.getName().toLowerCase().contains("log4j")) {
          return false;
        }
      }
    }

    // Simply considering having multiple agents
    // Formatting agent file list, Java 7 style
    StringBuilder agentFiles = new StringBuilder();
    boolean first = true;
    for (File agentFile : getAgentFilesFromVMArguments()) {
      if (first) {
        first = false;
      } else {
        agentFiles.append(", ");
      }
      agentFiles.append('"');
      agentFiles.append(agentFile.getAbsolutePath());
      agentFiles.append('"');
    }
    System.err.println(
        "Info: multiple JVM agents detected, found "
            + agentFiles
            + ". Loading multiple APM/Tracing agent is not a recommended or supported configuration."
            + "Please set the environment variable DD_INJECT_FORCE or the system property dd.inject.force to TRUE to load Datadog APM/Tracing agent.");
    return true;
  }

  public static void main(final String[] args) {
    AgentJar.main(args);
  }

  @SuppressForbidden
  private static synchronized URL installAgentJar(final Instrumentation inst)
      throws IOException, URISyntaxException {
    // First try Code Source
    final CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();
    if (codeSource != null) {
      URL ddJavaAgentJarURL = codeSource.getLocation();
      if (ddJavaAgentJarURL != null) {
        final File ddJavaAgentJarPath = new File(ddJavaAgentJarURL.toURI());

        if (!ddJavaAgentJarPath.isDirectory()) {
          return appendAgentToBootstrapClassLoaderSearch(
              inst, ddJavaAgentJarURL, ddJavaAgentJarPath);
        }
      }
    }

    System.err.println("Could not get bootstrap jar from code source, using -javaagent arg");
    File javaagentFile = getAgentFileFromJavaagentArg(getAgentFilesFromVMArguments());
    if (javaagentFile != null) {
      URL ddJavaAgentJarURL = javaagentFile.toURI().toURL();
      return appendAgentToBootstrapClassLoaderSearch(inst, ddJavaAgentJarURL, javaagentFile);
    }

    System.err.println(
        "Could not get agent jar from -javaagent arg, using ClassLoader#getResource");
    javaagentFile = getAgentFileUsingClassLoaderLookup();
    if (!javaagentFile.isDirectory()) {
      URL ddJavaAgentJarURL = javaagentFile.toURI().toURL();
      return appendAgentToBootstrapClassLoaderSearch(inst, ddJavaAgentJarURL, javaagentFile);
    }

    throw new IllegalStateException(
        "Could not determine agent jar location, not installing tracing agent");
  }

  private static URL appendAgentToBootstrapClassLoaderSearch(
      Instrumentation inst, URL ddJavaAgentJarURL, File javaagentFile) throws IOException {
    checkJarManifestMainClassIsThis(ddJavaAgentJarURL);
    inst.appendToBootstrapClassLoaderSearch(new JarFile(javaagentFile));
    return ddJavaAgentJarURL;
  }

  @SuppressForbidden
  private static File getAgentFileFromJavaagentArg(List<File> agentFiles) {
    if (agentFiles.isEmpty()) {
      System.err.println("Could not get bootstrap jar from -javaagent arg: no argument specified");
      return null;
    } else if (agentFiles.size() > 1) {
      System.err.println(
          "Could not get bootstrap jar from -javaagent arg: multiple javaagents specified");
      return null;
    } else {
      return agentFiles.get(0);
    }
  }

  @SuppressForbidden
  private static List<File> getAgentFilesFromVMArguments() {
    if (agentFiles == null) {
      agentFiles = new ArrayList<>();
      // ManagementFactory indirectly references java.util.logging.LogManager
      // - On Oracle-based JDKs after 1.8
      // - On IBM-based JDKs since at least 1.7
      // This prevents custom log managers from working correctly
      // Use reflection to bypass the loading of the class~
      for (final String argument : JavaVirtualMachine.getVmOptions()) {
        if (argument.startsWith(JAVA_AGENT_ARGUMENT)) {
          int index = argument.indexOf('=', JAVA_AGENT_ARGUMENT.length());
          String agentPathname =
              argument.substring(
                  JAVA_AGENT_ARGUMENT.length(), index == -1 ? argument.length() : index);
          File agentFile = new File(agentPathname);
          if (agentFile.exists() && agentFile.isFile()) {
            agentFiles.add(agentFile);
          } else {
            System.err.println(
                "Could not get bootstrap jar from -javaagent arg: unable to find javaagent file: "
                    + agentFile);
          }
        }
      }
    }
    return agentFiles;
  }

  @SuppressForbidden
  private static File getAgentFileUsingClassLoaderLookup() throws URISyntaxException {
    File javaagentFile;
    URL thisClassUrl;
    String thisClassResourceName = thisClass.getName().replace('.', '/') + ".class";
    ClassLoader classLoader = thisClass.getClassLoader();
    if (classLoader == null) {
      thisClassUrl = ClassLoader.getSystemResource(thisClassResourceName);
    } else {
      thisClassUrl = classLoader.getResource(thisClassResourceName);
    }

    if (thisClassUrl == null) {
      throw new IllegalStateException(
          "Could not locate agent bootstrap class resource, not installing tracing agent");
    }

    javaagentFile = new File(new URI(thisClassUrl.getFile().split("!")[0]));
    return javaagentFile;
  }

  private static void checkJarManifestMainClassIsThis(final URL jarUrl) throws IOException {
    final URL manifestUrl = new URL("jar:" + jarUrl + "!/META-INF/MANIFEST.MF");
    final String mainClassLine = "Main-Class: " + thisClass.getCanonicalName();
    try (final BufferedReader reader =
        new BufferedReader(new InputStreamReader(manifestUrl.openStream(), UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.equals(mainClassLine)) {
          return;
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
