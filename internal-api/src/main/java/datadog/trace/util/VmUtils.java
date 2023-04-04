package datadog.trace.util;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VmUtils {

  private static final Logger log = LoggerFactory.getLogger(VmUtils.class);

  public static Collection<String> getVMArguments() {
    return VmArgsHolder.ARGS;
  }

  private static final class VmArgsHolder {
    private static final Collection<String> ARGS =
        Collections.unmodifiableCollection(new LinkedHashSet<>(getVMArguments()));

    private static Collection<String> getVMArguments() {
      try {
        // Try Oracle-based
        // IBM Semeru Runtime 1.8.0_345-b01 will throw UnsatisfiedLinkError here.
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
        return (List<String>) vmManagementClass.getMethod("getVmArguments").invoke(vmManagement);

      } catch (final ReflectiveOperationException | UnsatisfiedLinkError e) {
        try { // Try IBM-based.
          final Class<?> VMClass = Class.forName("com.ibm.oti.vm.VM");
          final String[] argArray = (String[]) VMClass.getMethod("getVMArgs").invoke(null);
          return Arrays.asList(argArray);

        } catch (final ReflectiveOperationException e1) {
          // Fallback to default
          log.warn(
              "Unable to get VM args through reflection. A custom java.util.logging.LogManager may not work correctly");
          return ManagementFactory.getRuntimeMXBean().getInputArguments();
        }
      }
    }
  }
}
