package datadog.cli;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class CLIHelper {
  public static final CLIHelper ARGS = new CLIHelper(initJvmArgs());

  private final HashSet<String> args;

  public CLIHelper(List<String> args) {
    this.args = new HashSet<>(args);
  }

  public HashSet<String> getJvmArgs() {
    return this.args;
  }

  public boolean contains(String argument) {
    return this.args.contains(argument);
  }

  private static List<String> initJvmArgs() {
    // If linux OS, use procfs
    // TODO: equals, or contains?
    if (System.getProperty("os.name").equalsIgnoreCase("linux")) {
      // Get the current process PID from /proc/self/status
      try {
        // Get the JVM arguments from /proc/self/cmdline
        return getJvmArgsFromProcCmdline();
      } catch (IOException e) {
        // ignore exception, try other methods
      }
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
      System.err.println(
          "WARNING: Unable to get VM args through reflection. A custom java.util.logging.LogManager may not work correctly");
      return ManagementFactory.getRuntimeMXBean().getInputArguments();
    } catch (final Throwable t) {
      // Throws InvocationTargetException on modularized applications
      // with non-opened java.management module
      System.err.println("WARNING: Unable to get VM args using managed beans");
    }
    return Collections.emptyList();
  }

  private static List<String> getJvmArgsFromProcCmdline() throws IOException {
    // Read /proc/[pid]/cmdline to get JVM arguments
    BufferedReader argsReader = new BufferedReader(new FileReader("/proc/self/cmdline"));
    String cmdLine = argsReader.readLine();
    if (cmdLine != null) {
      // Return JVM arguments as a list of strings split by null characters
      return Arrays.asList(cmdLine.split("\0"));
    } else {
      return null;
    }
  }
}
