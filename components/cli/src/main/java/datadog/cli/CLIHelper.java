package datadog.cli;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CLIHelper {
  private static final Map<String, String> VM_ARGS = findVmArgs();

  public static Map<String, String> getVmArgs() {
    return VM_ARGS;
  }

  @SuppressForbidden
  private static Map<String, String> findVmArgs() {
    List<String> rawArgs;

    // Try ProcFS on Linux
    try {
      if (isLinux()) {
        Path cmdlinePath = Paths.get("/proc/self/cmdline");
        if (Files.exists(cmdlinePath)) {
          try (BufferedReader in = Files.newBufferedReader(cmdlinePath)) {
            rawArgs = Arrays.asList(in.readLine().split("\0"));
            return parseVmArgs(rawArgs);
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
      rawArgs = (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);
      return parseVmArgs(rawArgs);
    } catch (final ReflectiveOperationException | UnsatisfiedLinkError ignored) {
      // Ignored exception
    }

    // Try IBM-based.
    try {
      final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
      final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
      rawArgs = Arrays.asList(argArray);
      return parseVmArgs(rawArgs);
    } catch (final ReflectiveOperationException ignored) {
      // Ignored exception
    }

    // Fallback to default
    try {
      rawArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
      return parseVmArgs(rawArgs);
    } catch (final Throwable t) {
      // Throws InvocationTargetException on modularized applications
      // with non-opened java.management module
      System.err.println("WARNING: Unable to get VM args using managed beans");
    }
    return Collections.emptyMap();
  }

  private static Map<String, String> parseVmArgs(List<String> args) {
    Map<String, String> result = new HashMap<>();

    // For now, we only support values on system properties (-D arguments)
    for (String arg : args) {
      if (arg.startsWith("-D")) {
        // Handle system properties (-D arguments)
        int equalsIndex = arg.indexOf('=');

        if (equalsIndex >= 0) {
          // Key-value pair
          String key = arg.substring(0, equalsIndex);
          String value = arg.substring(equalsIndex + 1);
          result.put(key, value);
        } else {
          // Just a key with no value
          result.put(arg, "");
        }
      } else {
        // Any other type of VM argument
        result.put(arg, "");
      }
    }

    return result;
  }

  private static boolean isLinux() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
  }
}
