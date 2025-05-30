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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Fetches and captures the JVM options and command arguments. */
class CommandLine {
  static final List<String> VM_OPTIONS = findVmOptions();
  static final String[] FULL_CMD = findFullCommand();
  static final String CMD = getCommand();
  static final List<String> CMD_ARGUMENTS = getCommandArguments();

  @SuppressForbidden
  private static List<String> findVmOptions() {
    // Try ProcFS on Linux
    try {
      if (isLinux()) {
        Path cmdlinePath = Paths.get("/proc/self/cmdline");
        if (Files.exists(cmdlinePath) && Files.isReadable(cmdlinePath)) {
          try (BufferedReader in = Files.newBufferedReader(cmdlinePath)) {
            return Arrays.asList(in.readLine().split("\0"));
          }
        }
      }
    } catch (Throwable ignored) {
      // Ignored exception
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

  private static String[] findFullCommand() {
    // Besides "sun.java.command" property is not an standard, all main JDKs has set this
    // property.
    // Tested on:
    // - OracleJDK, OpenJDK, AdoptOpenJDK, IBM JDK, Azul Zulu JDK, Amazon Coretto JDK
    String command = SystemProperties.getOrDefault("sun.java.command", "").trim();
    if (command.isEmpty()) {
      return new String[0];
    }
    return command.split(" ");
  }

  static String getCommand() {
    return FULL_CMD.length == 0 ? null : FULL_CMD[0];
  }

  static List<String> getCommandArguments() {
    if (FULL_CMD.length == 0) {
      return emptyList();
    } else {
      return Arrays.asList(FULL_CMD).subList(1, FULL_CMD.length);
    }
  }

  private static List<String> getArgumentsFromFile(String argFile) {
    String filename = argFile.substring(1);
    Path path = Paths.get(filename);
    if (!Files.exists(path) || !Files.isReadable(path)) {
      return singletonList(argFile);
    }
    try (Stream<String> lines = Files.lines(path)) {
      return lines.flatMap(CommandLine::getArgumentFromLine).collect(Collectors.toList());
    } catch (IOException e) {
      return singletonList(argFile);
    }
  }

  private static Stream<String> getArgumentFromLine(String line) {
    return Stream.of(line.split("[ \\t\\n\\r\\f]"));
  }
}
