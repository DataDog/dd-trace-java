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
          ex,
          "datadog.trace.util.throwable.FatalAgentMisconfigurationError"
      )) {
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
      final Instrumentation inst
  ) throws IOException, URISyntaxException, ReflectiveOperationException {
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
    if (isAotTraining(agentArgs, inst)) {
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
              "start",
              Object.class,
              Instrumentation.class,
              URL.class,
              String.class
      );
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
          "Warning: dd-java-agent is being initialized more than once. Please check that you "
          + "are defining -javaagent:dd-java-agent.jar only once."
      );
      return true;
    }
    initialized = true;
    return false;
  }

  /**
   * Returns {@code true} if the JVM is running a JDK diagnostic/development tool rather than a user
   * application, in which case the agent should abort early.
   *
   * <p><b>How to discover new entries when a tool is missed:</b>
   *
   * <ol>
   *   <li>Build a minimal javaagent JAR whose {@code premain} prints {@code
   *       System.getProperty("jdk.module.main")} and {@code System.getProperty("sun.java.command")}
   *       then calls {@code System.exit(0)}.
   *   <li>For <b>JDK 9+ tools</b>, inject it via {@code JAVA_TOOL_OPTIONS}:
   *       <pre>JAVA_TOOL_OPTIONS="-javaagent:/path/to/agent.jar" $JAVA_HOME/bin/&lt;tool&gt;</pre>
   *       The value of {@code jdk.module.main} is the module name to add to the first switch.
   *   <li>For <b>JDK 8 tools</b> (or non-modular IBM/OpenJ9 tools), inject the same way; the value
   *       of {@code sun.java.command} (up to the first space) is the main-class name to add to the
   *       second switch.
   * </ol>
   *
   * <p>Native binaries (e.g. {@code jitserver}, {@code asprof}) report both properties as {@code
   * null} and are automatically ignored — no switch entry needed for them.
   */
  static boolean isJdkTool() {
    String moduleMain = SystemProperties.get("jdk.module.main");
    if (null != moduleMain && !moduleMain.isEmpty()) {
      char firstChar = moduleMain.charAt(0);
      if (firstChar == 'j') {
        // Standard JDK 9+ module-based tools (module names start with 'java.' or 'jdk.')
        switch (moduleMain) {
          // keytool
          case "java.base":
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
      } else if (firstChar == 'o') {
        // OpenJ9 / Semeru 11+ module-based tools (module names start with 'openj9.')
        switch (moduleMain) {
          // jextract, jpackcore
          case "openj9.dtfj":
          // jdmpview
          case "openj9.dtfjview":
          case
              // traceformat
          "openj9.traceformat":
            return true;
        }
      }
    }
    // Handles JDK 8 tools (IBM J9 and standard JDK 8 vendors)
    // jdk.module.main is only set for JDK 9+ module-based tools (already handled above)
    String command = SystemProperties.get("sun.java.command");
    if (null != command && !command.isEmpty()) {
      // substring on first space
      int firstSpace = command.indexOf(' ');
      String mainClass = firstSpace > 0 ? command.substring(0, firstSpace) : command;
      switch (mainClass) {
        // IBM J9 JDK 8 specific tool main classes
        // keytool
        case "com.ibm.crypto.tools.KeyTool":
        // kinit
        case "com.ibm.security.krb5.internal.tools.Kinit":
        // klist
        case "com.ibm.security.krb5.internal.tools.Klist":
        // ktab
        case "com.ibm.security.krb5.internal.tools.Ktab":
        // jdmpview
        case "com.ibm.jvm.dtfjview.DTFJView":
        // jextract
        case "com.ibm.jvm.j9.dump.extract.Main":
        // ikeyman
        case "com.ibm.gsk.ikeyman.Ikeyman":
        // ikeycmd
        case "com.ibm.gsk.ikeyman.ikeycmd":
        // tnameserv
        case "com.ibm.CosNaming.TransientNameServer":
        // idlj
        case "com.ibm.idl.toJavaPortable.Compile":
        // OpenJ9 / Semeru 8 specific tool main classes (OpenJ9 reimplementation of HotSpot tools)
        // jcmd
        case "openj9.tools.attach.diagnostics.tools.Jcmd":
        // jps
        case "openj9.tools.attach.diagnostics.tools.Jps":
        // jstat
        case "openj9.tools.attach.diagnostics.tools.Jstat":
        // jmap
        case "openj9.tools.attach.diagnostics.tools.Jmap":
        // jstack
        case "openj9.tools.attach.diagnostics.tools.Jstack":
        // traceformat
        case "com.ibm.jvm.TraceFormat":
        // Standard JDK 8 tool main classes (shared by IBM J9 and Oracle/OpenJDK 8)
        // jar
        case "sun.tools.jar.Main":
        // javac
        case "com.sun.tools.javac.Main":
        // javadoc
        case "com.sun.tools.javadoc.Main":
        // javap
        case "com.sun.tools.javap.Main":
        // javah
        case "com.sun.tools.javah.Main":
        // keytool (Oracle/OpenJDK 8)
        case "sun.security.tools.keytool.Main":
        // jarsigner
        case "sun.security.tools.jarsigner.Main":
        // policytool
        case "sun.security.tools.policytool.PolicyTool":
        // jdb
        case "com.sun.tools.example.debug.tty.TTY":
        // jdeps
        case "com.sun.tools.jdeps.Main":
        // rmic
        case "sun.rmi.rmic.Main":
        // rmiregistry
        case "sun.rmi.registry.RegistryImpl":
        // rmid
        case "sun.rmi.server.Activation":
        // extcheck
        case "com.sun.tools.extcheck.Main":
        // serialver
        case "sun.tools.serialver.SerialVer":
        // native2ascii
        case "sun.tools.native2ascii.Main":
        // wsgen
        case "com.sun.tools.internal.ws.WsGen":
        // wsimport
        case "com.sun.tools.internal.ws.WsImport":
        // xjc
        case "com.sun.tools.internal.xjc.Driver":
        // schemagen
        case "com.sun.tools.internal.jxc.SchemaGenerator":
        // jrunscript
        case "com.sun.tools.script.shell.Main":
        // jjs (Nashorn JS shell, JDK 8)
        case "jdk.nashorn.tools.Shell":
        // jconsole
        case "sun.tools.jconsole.JConsole":
        // appletviewer
        case "sun.applet.Main":
        // tnameserv
        case "com.sun.corba.se.impl.naming.cosnaming.TransientNameServer":
        // (Oracle/OpenJDK 8)
        // idlj (Oracle/OpenJDK 8)
        case "com.sun.tools.corba.se.idl.toJavaPortable.Compile":
        // orbd
        case "com.sun.corba.se.impl.activation.ORBD":
        // servertool
        case "com.sun.corba.se.impl.activation.ServerTool":
        // jps
        case "sun.tools.jps.Jps":
        // jstack
        case "sun.tools.jstack.JStack":
        // jmap
        case "sun.tools.jmap.JMap":
        // jinfo
        case "sun.tools.jinfo.JInfo":
        // jhat
        case "com.sun.tools.hat.Main":
        // jstat
        case "sun.tools.jstat.Jstat":
        // jstatd
        case "sun.tools.jstatd.Jstatd":
        // jcmd
        case "sun.tools.jcmd.JCmd":
        // jfr, backported to OpenJDK 8 in 8u262 (JEP 328
        case "jdk.jfr.internal.tool.Main":
        // backport, July 2020)
        // jsadebugd
        case "sun.jvm.hotspot.jdi.SADebugServer":
        // hsdb (HotSpot SA GUI debugger, JDK 8)
        case "sun.jvm.hotspot.HSDB":
        case
            // clhsdb (HotSpot SA command-line debugger, JDK 8)
        "sun.jvm.hotspot.CLHSDB":
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
        + "Please set the environment variable DD_INJECT_FORCE or the system property dd.inject.force to TRUE to load Datadog APM/Tracing agent."
    );
    return true;
  }

  public static void main(final String[] args) {
    AgentJar.main(args);
  }

  @SuppressForbidden
  private static synchronized URL installAgentJar(final Instrumentation inst)
      throws IOException,
      URISyntaxException {
    // First try Code Source
    final CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();
    if (codeSource != null) {
      URL ddJavaAgentJarURL = codeSource.getLocation();
      if (ddJavaAgentJarURL != null) {
        final File ddJavaAgentJarPath = new File(ddJavaAgentJarURL.toURI());

        if (!ddJavaAgentJarPath.isDirectory()) {
          return appendAgentToBootstrapClassLoaderSearch(
              inst,
              ddJavaAgentJarURL,
              ddJavaAgentJarPath
          );
        }
      }
    }

    System.err.println("Could not get bootstrap jar from code source, using -javaagent arg");
    File javaagentFile = getAgentFileFromJavaagentArg(getAgentFilesFromVMArguments());
    if (javaagentFile != null) {
      URL ddJavaAgentJarURL = javaagentFile.toURI().toURL();
      return appendAgentToBootstrapClassLoaderSearch(inst, ddJavaAgentJarURL, javaagentFile);
    }

    System.err.println("Could not get agent jar from -javaagent arg, using ClassLoader#getResource");
    javaagentFile = getAgentFileUsingClassLoaderLookup();
    if (!javaagentFile.isDirectory()) {
      URL ddJavaAgentJarURL = javaagentFile.toURI().toURL();
      return appendAgentToBootstrapClassLoaderSearch(inst, ddJavaAgentJarURL, javaagentFile);
    }

    throw new IllegalStateException(
        "Could not determine agent jar location, not installing tracing agent"
    );
  }

  private static URL appendAgentToBootstrapClassLoaderSearch(
      Instrumentation inst,
      URL ddJavaAgentJarURL,
      File javaagentFile
  ) throws IOException {
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
          "Could not get bootstrap jar from -javaagent arg: multiple javaagents specified"
      );
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
                  JAVA_AGENT_ARGUMENT.length(),
                  index == -1 ? argument.length() : index
          );
          File agentFile = new File(agentPathname);
          if (agentFile.exists() && agentFile.isFile()) {
            agentFiles.add(agentFile);
          } else {
            System.err.println(
                "Could not get bootstrap jar from -javaagent arg: unable to find javaagent file: " + agentFile
            );
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
          "Could not locate agent bootstrap class resource, not installing tracing agent"
      );
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
        + "'. Make sure you don't have this .class-file anywhere, besides dd-java-agent.jar"
    );
  }

  /**
   * Returns {@code true} if the JVM is training, i.e. writing to a CDS/AOT archive.
   */
  private static boolean isAotTraining(String agentArgs, Instrumentation inst) {
    if (!JavaVirtualMachine.isJavaVersionAtLeast(25)) {
      // agent doesn't support training mode before Java 25
      return false;
    } else if ("aot_training".equalsIgnoreCase(agentArgs)) {
      // training mode explicitly enabled via -javaagent
      return true;
    } else if ("false".equalsIgnoreCase(EnvironmentVariables.get("DD_DETECT_AOT_TRAINING_MODE"))) {
      // detection of training mode disabled via DD_DETECT_AOT_TRAINING_MODE=false
      return false;
    } else {
      // check JVM status
      return AdvancedAgentChecks.isAotTraining(inst);
    }
  }
}
