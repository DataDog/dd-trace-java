package datadog.nativeloader;

public class TestPlatformSpec extends PlatformSpec {
  public static final String MAC = "mac";
  public static final String WINDOWS = "win";
  public static final String LINUX = "linux";

  public static final String UNSUPPORTED_OS = "unsupported";

  public static final String X86_32 = "x86_32";
  public static final String X86_64 = "x86_64";

  public static final String ARM32 = "arm32";
  public static final String AARCH64 = "aarch64";

  public static final String UNSUPPORTED_ARCH = "unsupported";

  public static final boolean GLIBC = false;
  public static final boolean MUSL = true;

  public static final PlatformSpec of(String os, String arch) {
    return new TestPlatformSpec(os, arch, false);
  }

  public static final TestPlatformSpec of(String os, String arch, boolean isMusl) {
    return new TestPlatformSpec(os, arch, isMusl);
  }

  private final String os;
  private final String arch;
  private final boolean isMusl;

  private TestPlatformSpec(String os, String arch, boolean isMusl) {
    this.os = os;
    this.arch = arch;
    this.isMusl = isMusl;
  }

  @Override
  public boolean isLinux() {
    return this.isOs(LINUX);
  }

  @Override
  public boolean isMac() {
    return this.isOs(MAC);
  }

  @Override
  public boolean isWindows() {
    return this.isOs(WINDOWS);
  }

  private boolean isOs(String osConst) {
    return osConst.equals(this.os);
  }

  @Override
  public boolean isX86_32() {
    return this.isArch(X86_32);
  }

  @Override
  public boolean isAarch64() {
    return this.isArch(AARCH64);
  }

  @Override
  public boolean isArm32() {
    return this.isArch(ARM32);
  }

  @Override
  public boolean isX86_64() {
    return this.isArch(X86_64);
  }

  @Override
  public boolean isMusl() {
    return this.isMusl;
  }

  private boolean isArch(String archConst) {
    return archConst.equals(this.arch);
  }
}
