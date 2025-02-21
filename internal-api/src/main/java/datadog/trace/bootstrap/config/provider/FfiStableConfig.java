package datadog.trace.bootstrap.config.provider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FfiStableConfig implements AutoCloseable {
  private long configurator;
  private static native long new_configurator(boolean debug_logs);
  private static native void override_local_path(long configurator, String local_path);
  private static native void override_fleet_path(long configurator, String fleet_path);
  private static native void drop_configurator(long configurator);
  private static native void get_configuration(long configurator, Object config);
  private static final Logger log = LoggerFactory.getLogger(StableConfigSource.class);

  public class StableConfigResult {
    public String config_id;
    public Map<String, String> local_configuration;
    public Map<String, String> fleet_configuration;
  }

  static { 
    // Load locally-built library first, if possible
    boolean localLoadSuccess = false;
    try {
        System.loadLibrary("datadog_library_config_java");
        localLoadSuccess = true;
    } catch (Throwable t) {
      // Couldn't load locally
      localLoadSuccess = false;
    }

    if (!localLoadSuccess) {
      loadNativeLibraryFromJar();
    }
  }

  private static void loadNativeLibraryFromJar() {
    try {
      File nativeLib = extractLib();
      System.load(nativeLib.getAbsolutePath());
      nativeLib.getParentFile().deleteOnExit();
    } catch (Throwable t) {
      log.warn("Failed to load datadog_library_config_java library: {}", t.getMessage());
    }
  }

  private enum OsType {
    LINUX_x86_64_GLIBC,
    LINUX_x86_64_MUSL,
    LINUX_AARCH64_GLIBC,
    LINUX_AARCH64_MUSL,
    MAC_OS_x86_64,
    MAC_OS_AARCH64,
    WINDOWS_64,
    UNKNOWN
  }

  private enum LibC {
    GLIBC,
    MUSL
  }

  private static File extractLib() throws UnsupportedOperationException, IOException  {
    ClassLoader classLoader = FfiStableConfig.class.getClassLoader();
    if (classLoader == null) {
        classLoader = ClassLoader.getSystemClassLoader();
    }
    OsType osType = getOsType();
    if (osType == OsType.UNKNOWN) {
        throw new UnsupportedOperationException("Unknown OS type");
    }
    String nativeLib = getNativeLib(osType);

    Path tempDir = Files.createTempDirectory("libdatadog-java");
    String jarLibPath = "native_libs/" + nativeLib;
    InputStream input = classLoader.getResourceAsStream(jarLibPath);
    if (input == null) {
        throw new IOException("Not found: " + jarLibPath);
    }

    File dest = new File(tempDir.toFile(), new File(jarLibPath).getName());
    try {
        copyToFile(input, dest);
    } finally {
        input.close();
    }
    dest.deleteOnExit();
    return dest;
  }

  private static void copyToFile(InputStream input, File dest) throws IOException {
    OutputStream os = null;
    try {
        os = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        while (true) {
            int r = input.read(buf);
            if (r == -1) {
                break;
            }
            os.write(buf, 0, r);
        }
        os.flush();
    } finally {
        if (os != null) {
            os.close();
        }
    }
  }

  private static OsType getOsType() {
    String os = System.getProperty("os.name");
    String arch = System.getProperty("os.arch");

    if (!"amd64".equals(arch) && !"x86_64".equals(arch) && !"aarch64".equals(arch)) {
      return OsType.UNKNOWN;
    }
    boolean isAarch64 = "aarch64".equals(arch);

    if ("Linux".equals(os)) {
      LibC libc = getLibC();
      if (libc == LibC.MUSL) {
          return isAarch64 ? OsType.LINUX_AARCH64_MUSL : OsType.LINUX_x86_64_MUSL;
      } else {
          return isAarch64 ? OsType.LINUX_AARCH64_GLIBC : OsType.LINUX_x86_64_GLIBC;
      }
    }

    if ("Mac OS X".equals(os)) {
        return isAarch64 ? OsType.MAC_OS_AARCH64 : OsType.MAC_OS_x86_64;
    }

    if (os != null && os.toLowerCase(Locale.ENGLISH).contains("windows")) {
        return OsType.WINDOWS_64;
    }

    return OsType.UNKNOWN;
  }

  private static LibC getLibC() {
    // On Linux we need to check whether or not we are using musl or glibc.
    // This can be done by checking the /proc/self/maps file for the C library
    // that the JVM is using.
    // If the JVM is using musl, the C library will be named libc.musl-<ARCH>.so.1
    // or ld-musl-<ARCH>.so.1
    // In any other case, we assume glibc.
    try (Scanner sc = new Scanner(new File("/proc/self/maps"), "ISO-8859-1")) {
      while (sc.hasNextLine()) {
          String module = sc.nextLine();
          // in recent versions of Alpine, the name of the C library
          // is /lib/ld-musl-<ARCH>.so.1. /lib/libc.musl-<ARCH>.so.1
          // symlinks there
          if (module.contains("libc.musl-") || module.contains("ld-musl-")) {
              return LibC.MUSL;
          }
      }
    } catch (IOException e) {
        log.warn("Unable to read jvm maps; assuming glibc", e);
    }
    return LibC.GLIBC;
  }

  private static String getNativeLib(OsType type) {
    switch(type) {
        case LINUX_x86_64_GLIBC:
            return "linux/x86_64/glibc/libdatadog_library_config_java.so";
        case LINUX_x86_64_MUSL:
            return "linux/x86_64/musl/libdatadog_library_config_java.so";
        case LINUX_AARCH64_GLIBC:
            return "linux/aarch64/glibc/libdatadog_library_config_java.so";
        case LINUX_AARCH64_MUSL:
            return "linux/aarch64/musl/libdatadog_library_config_java.so";
        case MAC_OS_x86_64:
            return "macos/x86_64/libdatadog_library_config_java.dylib";
        case MAC_OS_AARCH64:
            return "macos/aarch64/libdatadog_library_config_java.dylib";
        case WINDOWS_64:
            return "windows/x86_64/datadog_library_config_java.dll";
        default:
            return "";
    }
  }

  public FfiStableConfig(boolean debug_logs) {
    configurator = new_configurator(debug_logs);
  }

  public void setLocalPath(String localPath) {
    override_local_path(configurator, localPath);
  }

  public void setFleetPath(String fleetPath) {
    override_fleet_path(configurator, fleetPath);
  }

  public StableConfigResult getConfiguration() {
    StableConfigResult cfg = new StableConfigResult();
    get_configuration(configurator, cfg);
    return cfg;
  }

  @Override
  public void close() {
    if (configurator != 0) {
      drop_configurator(configurator);
      configurator = 0;
    }
  }
}
