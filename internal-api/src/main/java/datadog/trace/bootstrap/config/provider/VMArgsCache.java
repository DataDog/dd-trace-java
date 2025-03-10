package datadog.trace.bootstrap.config.provider;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VMArgsCache {
  private static final class Singleton {
    private static VMArgsCache INSTANCE = null;
  }

  // TODO: Make this a smarter data structure
  private final List<String> args;
  private boolean initialized;

  public VMArgsCache(List<String> args) {
    this.args = args;
  }

  public List<String> getArgs() {
    return this.args;
  }

  public boolean contains(String argument) {
    for (String arg : this.args) {
      if (arg.equals(argument)) {
        return true;
      }
    }
    return false;
  }

  @SuppressForbidden
  public static List<String> getVMArguments() {
    if (Singleton.INSTANCE == null) {
      Singleton.INSTANCE = new VMArgsCache(getVMArgumentsThroughReflection());
    }
    return Singleton.INSTANCE.getArgs();
  }

  private static List<String> getVMArgumentsThroughReflection() {
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
}
