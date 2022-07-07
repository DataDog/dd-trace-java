package datadog.trace.agent.tooling;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

public class ClassWriterHelper {

  public static String getCommonSuperClass(String type1, String type2, ClassLoader classLoader) {
    // We cannot use ASM's getCommonSuperClass because it tries to load super class with
    // ClassLoader which in some circumstances can lead to
    // java.lang.LinkageError: loader (instance of  sun/misc/Launcher$AppClassLoader): attempted
    // duplicate class definition for name: "okhttp3/RealCall"
    // for more info see:
    // https://stackoverflow.com/questions/69563714/linkageerror-attempted-duplicate-class-definition-when-dynamically-instrument
    ClassFileLocator locator =
        AgentStrategies.locationStrategy().classFileLocator(classLoader, null);
    TypePool tp =
        new TypePool.Default.WithLazyResolution(
            TypePool.CacheProvider.Simple.withObjectType(),
            locator,
            TypePool.Default.ReaderMode.FAST);
    try {
      TypeDescription td1 = tp.describe(type1.replace('/', '.')).resolve();
      TypeDescription td2 = tp.describe(type2.replace('/', '.')).resolve();
      TypeDescription common = null;
      if (td1.isAssignableFrom(td2)) {
        common = td1;
      } else if (td2.isAssignableFrom(td1)) {
        common = td2;
      } else {
        if (td1.isInterface() || td2.isInterface()) {
          common = tp.describe("java.lang.Object").resolve();
        } else {
          common = td1;
          do {
            common = common.getSuperClass().asErasure();
          } while (!common.isAssignableFrom(td2));
        }
      }
      return common.getInternalName();
    } catch (Exception ex) {
      // ExceptionHelper.logException(, ex, "getCommonSuperClass failed: ");
      return tp.describe("java.lang.Object").resolve().getInternalName();
    }
  }
}
