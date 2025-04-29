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

/** Helper class for retrieving and parsing JVM arguments. */
public final class CLIHelper {
  private static final List<String> VM_ARGS = findVmArgs();
  private static Map<String, String> VM_ARGS_MAP = Collections.emptyMap();
  private static int currentIndex = 0; // Tracks the last processed index in VM_ARGS

  public static List<String> getVmArgs() {
    return VM_ARGS;
  }

  public static String getArgValue(String key) {
    int numArgs = VM_ARGS.size();
    // Lazy population of cache
    synchronized (VM_ARGS_MAP) {
      // Double check after acquiring lock
      if (VM_ARGS_MAP.containsKey(key)) {
        return VM_ARGS_MAP.get(key);
      }
      if (currentIndex >= numArgs) {
        return null;
      }

      // Initialize cache if empty
      if (VM_ARGS_MAP.isEmpty()) {
        VM_ARGS_MAP = new HashMap<>();
      }

      // Process remaining args
      while (currentIndex < numArgs) {
        populateCache(VM_ARGS.get(currentIndex));
        currentIndex++;

        // Check if we found our key
        if (VM_ARGS_MAP.containsKey(key)) {
          return VM_ARGS_MAP.get(key);
        }
      }

      return null;
    }
  }

  private static void populateCache(String arg) {
    if (arg.startsWith("-D")) {
      int equalsIndex = arg.indexOf('=');
      if (equalsIndex >= 0) {
        String argKey = arg.substring(0, equalsIndex);
        String argValue = arg.substring(equalsIndex + 1);
        VM_ARGS_MAP.put(argKey, argValue);
      } else {
        VM_ARGS_MAP.put(arg, "");
      }
    } else {
      VM_ARGS_MAP.put(arg, "");
    }
  }

  public static boolean argExists(String key) {
    int numArgs = VM_ARGS.size();
    // Lazy population of cache
    synchronized (VM_ARGS_MAP) {
      // Double check after acquiring lock
      if (currentIndex >= numArgs) {
        return VM_ARGS_MAP.containsKey(key);
      }

      // Initialize cache if empty
      if (VM_ARGS_MAP.isEmpty()) {
        VM_ARGS_MAP = new HashMap<>();
      }

      // Process remaining args
      while (currentIndex < numArgs) {
        String arg = VM_ARGS.get(currentIndex);
        populateCache(arg);
        currentIndex++;

        // Check if we found our key
        if (key.equals(arg)) {
          return true;
        }
      }

      return false;
    }
  }

  @SuppressForbidden
  private static List<String> findVmArgs() {
    List<String> rawArgs;

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
      rawArgs = (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);
      return rawArgs;
    } catch (final ReflectiveOperationException | UnsatisfiedLinkError ignored) {
      // Ignored exception
    }

    // Try IBM-based.
    try {
      final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
      final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
      rawArgs = Arrays.asList(argArray);
      return rawArgs;
    } catch (final ReflectiveOperationException ignored) {
      // Ignored exception
    }

    // Fallback to default
    try {
      rawArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
      return rawArgs;
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
