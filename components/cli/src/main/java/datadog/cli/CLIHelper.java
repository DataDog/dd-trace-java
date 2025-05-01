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
import java.util.List;
import java.util.Locale;

public final class CLIHelper {
  private static final List<String> VM_ARGS = findVmArgs();

  public static List<String> getVmArgs() {
    return VM_ARGS;
  }

  @SuppressForbidden
  private static List<String> findVmArgs() {
    // Try ProcFS on Linux
    try {
      if (isLinux()) {
        Path cmdlinePath = Paths.get("/proc/self/cmdline");
        if (Files.exists(cmdlinePath)) {
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
    return Collections.emptyList();
  }

  private static boolean isLinux() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
  }
}
