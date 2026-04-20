package datadog.environment;

import static datadog.environment.OperatingSystem.isLinux;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/** Fetches and captures the JVM options. */
class JvmOptions {
  static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";
  static final String JDK_JAVA_OPTIONS = "JDK_JAVA_OPTIONS";
  final String[] PROCFS_CMDLINE = readProcFsCmdLine();
  final List<String> VM_OPTIONS = findVmOptions();

  @SuppressForbidden // split on single-character uses a fast path
  private String[] readProcFsCmdLine() {
    if (isLinux()) {
      try {
        Path cmdlinePath = Paths.get("/proc/self/cmdline");
        if (Files.exists(cmdlinePath) && Files.isReadable(cmdlinePath)) {
          try (BufferedReader in = Files.newBufferedReader(cmdlinePath)) {
            return in.readLine().split("\0");
          }
        }
      } catch (Throwable ignored) {
      }
    }
    return null;
  }

  @SuppressForbidden // Class.forName() as backup
  private List<String> findVmOptions() {
    // Try ProcFS on Linux
    if (PROCFS_CMDLINE != null) {
      return findVmOptionsFromProcFs(PROCFS_CMDLINE);
    }

    // Try Oracle-based
    // IBM Semeru Runtime 1.8.0_345-b01 will throw UnsatisfiedLinkError here.
    try {
      final Class<?> managementFactoryHelperClass =
          Class.forName("sun.management.ManagementFactoryHelper");
      final Class<?> vmManagementClass = Class.forName("sun.management.VMManagement");

      Object vmManagement;
      try {
        vmManagement =
            managementFactoryHelperClass.getDeclaredMethod("getVMManagement").invoke(null);
      } catch (final Throwable e) {
        // Older vm before getVMManagement() existed
        final Field field = managementFactoryHelperClass.getDeclaredField("jvm");
        field.setAccessible(true);
        vmManagement = field.get(null);
        field.setAccessible(false);
      }
      //noinspection unchecked
      return (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);
    } catch (final Throwable ignored) {
      // Ignored exception
    }

    // Try IBM-based.
    try {
      final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
      final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
      return asList(argArray);
    } catch (final Throwable ignored) {
      // Ignored exception
    }

    // Fallback to default
    try {
      return ManagementFactory.getRuntimeMXBean().getInputArguments();
    } catch (final Throwable t) {
      // Throws InvocationTargetException on modularized applications
      // with a non-opened java.management module
      System.err.println("WARNING: Unable to get VM args using managed beans");
    }
    return emptyList();
  }

  // Be aware that when running a native image, the command line in /proc/self/cmdline is just the
  // executable
  // Visible for testing
  List<String> findVmOptionsFromProcFs(String[] procfsCmdline) {
    // Create the list of VM options
    List<String> vmOptions = new ArrayList<>();
    // Look for the first self-standing argument that is not prefixed with "-" or end of VM options
    // while simultaneously, collect all arguments in the VM options
    // Starts from 1 as 0 is the java command itself (or native-image)
    for (int index = 1; index < procfsCmdline.length; index++) {
      String argument = procfsCmdline[index];
      // Inflate arg files
      if (argument.startsWith("@")) {
        vmOptions.addAll(getArgumentsFromFile(argument));
      }
      // Skip classpath argument (not part of VM options)
      else if ("-cp".equals(argument)) {
        index++;
      }
      // Check "-jar" or class name argument as the end of the VM options
      else if ("-jar".equals(argument) || !argument.startsWith("-")) {
        // End of VM options
        break;
      }
      // Otherwise add as a VM option
      else {
        vmOptions.add(argument);
      }
    }
    // Insert JDK_JAVA_OPTIONS at the start if present and supported
    List<String> jdkJavaOptions = getJdkJavaOptions();
    if (!jdkJavaOptions.isEmpty()) {
      vmOptions.addAll(0, jdkJavaOptions);
    }
    // Insert JAVA_TOOL_OPTIONS at the start if present
    List<String> javaToolOptions = getJavaToolOptions();
    if (!javaToolOptions.isEmpty()) {
      vmOptions.addAll(0, javaToolOptions);
    }
    return vmOptions;
  }

  private static List<String> getArgumentsFromFile(String argFile) {
    String filename = argFile.substring(1);
    Path path = Paths.get(filename);
    if (!Files.exists(path) || !Files.isReadable(path)) {
      return singletonList(argFile);
    }
    List<String> args = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(path)) {
        // Use default delimiters that match argfiles separator specification
        StringTokenizer tokenizer = new StringTokenizer(line);
        while (tokenizer.hasMoreTokens()) {
          args.add(tokenizer.nextToken());
        }
      }
      return args;
    } catch (IOException e) {
      return singletonList(argFile);
    }
  }

  private static List<String> getJavaToolOptions() {
    String javaToolOptions = EnvironmentVariables.getOrDefault(JAVA_TOOL_OPTIONS, "");
    return javaToolOptions.isEmpty() ? emptyList() : parseOptions(javaToolOptions);
  }

  private static List<String> getJdkJavaOptions() {
    if (!JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      return emptyList();
    }
    String sdkToolOptions = EnvironmentVariables.getOrDefault(JDK_JAVA_OPTIONS, "");
    return sdkToolOptions.isEmpty() ? emptyList() : parseOptions(sdkToolOptions);
  }

  /**
   * Parse the JAVA_TOOL_OPTIONS environment variable according the JVMTI specifications.
   *
   * @param javaToolOptions The JAVA_TOOL_OPTIONS environment variable.
   * @return The parsed JVM options.
   * @see <a
   *     href="https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html#tooloptions">JVMTI
   *     specifications</a>
   */
  static List<String> parseOptions(String javaToolOptions) {
    List<String> options = new ArrayList<>();
    StringBuilder option = new StringBuilder();
    boolean inQuotes = false;
    char quoteChar = 0;

    for (int i = 0; i < javaToolOptions.length(); i++) {
      char c = javaToolOptions.charAt(i);
      if (inQuotes) {
        if (quoteChar == c) {
          inQuotes = false;
        } else {
          option.append(c);
        }
      } else if (c == '"' || c == '\'') {
        inQuotes = true;
        quoteChar = c;
      } else if (Character.isWhitespace(c)) {
        if (option.length() > 0) {
          options.add(option.toString());
          option.setLength(0);
        }
      } else {
        option.append(c);
      }
    }
    if (option.length() > 0) {
      options.add(option.toString());
    }
    return options;
  }
}
