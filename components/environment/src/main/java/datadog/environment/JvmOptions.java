package datadog.environment;

import static datadog.environment.OperatingSystem.isLinux;
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
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;

/** Fetches and captures the JVM options. */
class JvmOptions {
  final String[] PROCFS_CMDLINE = readProcFsCmdLine();
  final List<String> VM_OPTIONS = findVmOptions();

  //    static {
  //      RuntimeException runtimeException = new RuntimeException();
  //      runtimeException.printStackTrace(System.out);
  //    }

  @SuppressForbidden // split on single-character uses fast path
  private String[] readProcFsCmdLine() {
    if (!isLinux()) {
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
      // Start at 1 to skip "java" command itself
      int index = 1;
      // Look for main class or "-jar", end of VM options
      for (; index < PROCFS_CMDLINE.length; index++) {
        if (!PROCFS_CMDLINE[index].startsWith("-") || "-jar".equals(PROCFS_CMDLINE[index])) {
          break;
        }
      }
      String[] vmOptions = new String[index - 1];
      System.arraycopy(PROCFS_CMDLINE, 1, vmOptions, 0, vmOptions.length);

      // TODO ARGFILES Need to be substituted

      // TODO JAVA_TOOLS_OPTIONS Need to be inserted before.
      //      String javaToolOptions = EnvironmentVariables.getOrDefault("JAVA_TOOL_OPTIONS", null);
      //      if (javaToolOptions != null) {
      //        parts.addAll(0, Arrays.asList(javaToolOptions.split(" ")));
      //      }

      return Arrays.asList(vmOptions);
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
      } catch (final NoSuchMethodException e) {
        // Older vm before getVMManagement() existed
        final Field field = managementFactoryHelperClass.getDeclaredField("jvm");
        field.setAccessible(true);
        vmManagement = field.get(null);
        field.setAccessible(false);
      }
      //noinspection unchecked
      return (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);
    } catch (final ReflectiveOperationException | UnsatisfiedLinkError ignored) {
      // Ignored exception
    }

    // Try IBM-based.
    try {
      final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
      final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
      return Arrays.asList(argArray);
    } catch (final ReflectiveOperationException ignored) {
      // Ignored exception
    }

    // Fallback to default
    try {
      return ManagementFactory.getRuntimeMXBean().getInputArguments();
    } catch (final Throwable t) {
      // Throws InvocationTargetException on modularized applications
      // with non-opened java.management module
      System.err.println("WARNING: Unable to get VM args using managed beans");
    }
    return emptyList();
  }

  @SuppressForbidden // split on single-character uses fast path
  private List<String> findFullCommand() {
    // Besides "sun.java.command" property is not an standard, all main JDKs has set this
    // property.
    // Tested on:
    // - OracleJDK, OpenJDK, AdoptOpenJDK, IBM JDK, Azul Zulu JDK, Amazon Coretto JDK
    String command = SystemProperties.getOrDefault("sun.java.command", "").trim();
    return command.isEmpty() ? emptyList() : Arrays.asList(command.split(" "));
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
        // Use default delimiters that matches argfiles separator specification
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

  private static List<String> split(String str, String delimiter) {
    List<String> parts = new ArrayList<>();
    StringTokenizer tokenizer = new StringTokenizer(str, delimiter);
    while (tokenizer.hasMoreTokens()) {
      parts.add(tokenizer.nextToken());
    }
    return parts;
  }
}
