package datadog.cli;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CLIHelper {
  public static final CLIHelper ARGS = new CLIHelper();

  private final Map<String, List<String>> args;

  public CLIHelper() {
    this.args = parseJvmArgs(initJvmArgs());
  }

  public List<String> getValues(String jvmArg) {
    System.out.println("MTOFF: arg IS " + jvmArg);
    return this.args.get(jvmArg);
  }

  @SuppressForbidden
  private static List<String> initJvmArgs() {
    // If linux OS, use procfs
    // TODO: equals, or contains?
    if (System.getProperty("os.name").equalsIgnoreCase("linux")) {
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
    BufferedReader argsReader = new BufferedReader(new FileReader("/proc/self/cmdline"));
    String cmdLine = argsReader.readLine();
    if (cmdLine != null) {
      // Return JVM arguments as a list of strings split by null characters
      return Arrays.asList(cmdLine.split("\0"));
    } else {
      return null;
    }
  }

  /**
   * Parses JVM arguments into a Map where the key is the argument name and the value is a List of
   * values. This allows for multiple values for the same key.
   *
   * <p>Handles the following formats: - -Dkey=value (system properties) - -X flags (like -Xmx2g) -
   * -XX flags (like -XX:+UseG1GC) - -javaagent with values - Other flags like -jar, -cp, etc.
   *
   * @param args List of JVM arguments to parse
   * @return Map containing parsed arguments with lists of values
   */
  public static Map<String, List<String>> parseJvmArgs(List<String> args) {
    Map<String, List<String>> parsedArgs = new HashMap<>();
    if (args == null) {
      return parsedArgs;
    }

    for (String arg : args) {
      if (arg == null || arg.isEmpty()) {
        continue;
      }

      // Handle system properties (-Dkey=value)
      if (arg.startsWith("-D")) {
        addEntryToMap('=', arg, parsedArgs);
        continue;
      }

      // Handle -XX flags
      if (arg.startsWith("-XX:")) {
        // -XX flags can have values after = (like -XX:MaxMetaspaceSize=128m)
        addEntryToMap('=', arg, parsedArgs);
        continue;
      }

      // Handle -javaagent
      if (arg.startsWith("-javaagent:")) {
        addEntryToMap(':', arg, parsedArgs);
        continue;
      }

      // Handle other flags that might have values
      // Note that -X flags will not be parsed into key-vals; they'll be caught by the value-less
      // case and stored as a key. Therefore, duplicate keys are not supported for -X flags
      if (arg.startsWith("-")) {
        addEntryToMap('=', arg, parsedArgs);
      }
    }

    return parsedArgs;
  }

  private static void addEntryToMap(
      char equalsOperator, String arg, Map<String, List<String>> parsedArgs) {
    int equalsIndex = arg.indexOf(equalsOperator);
    if (equalsIndex >= 0 && equalsIndex < (arg.length() - 1)) {
      String key = arg.substring(0, equalsIndex);
      String value = arg.substring(equalsIndex + 1);
      parsedArgs.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    } else {
      parsedArgs.computeIfAbsent(arg, k -> new ArrayList<>()).add(null);
    }
  }
}
