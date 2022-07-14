package datadog.telemetry;

import jnr.ffi.LibraryLoader;
import jnr.ffi.LibraryOption;
import jnr.ffi.Platform;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.Struct;
import jnr.ffi.annotations.Out;
import jnr.ffi.annotations.Transient;
import jnr.ffi.mapper.FromNativeConverter;
import jnr.ffi.mapper.ToNativeContext;
import jnr.ffi.mapper.ToNativeConverter;
import jnr.ffi.mapper.TypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Uname {
  private static final Logger log = LoggerFactory.getLogger(Uname.class);

  private static final LibC LIB_C =
      LibraryLoader.create(LibC.class)
          .option(LibraryOption.TypeMapper, new UtsNameMapper())
          .load("c");
  public static final UtsName UTS_NAME = determineUtsName();

  private static UtsName determineUtsName() {
    UtsName utsName = createUtsName(Runtime.getRuntime(LIB_C));

    try {
      int ret = LIB_C.uname(utsName);
      if (ret == 0) {
        return utsName;
      }
    } catch (RuntimeException rte) {
      log.warn("Cannot get host information from uname", rte);
    }

    return new EmptyUtsName();
  }

  public interface LibC {
    int uname(@Out @Transient UtsName name);
  }

  public interface UtsName {
    String sysname();

    String nodename();

    String release();

    String version();

    String machine();
  }

  private static class EmptyUtsName implements UtsName {
    @Override
    public String sysname() {
      return null;
    }

    @Override
    public String nodename() {
      return null;
    }

    @Override
    public String release() {
      return null;
    }

    @Override
    public String version() {
      return null;
    }

    @Override
    public String machine() {
      return null;
    }
  }

  private static UtsName createUtsName(Runtime runtime) {
    Platform platform = Platform.getNativePlatform();
    Platform.OS os = platform.getOS();
    switch (os) {
      case DARWIN:
        return new UtsNameMacOs(runtime);
      case LINUX:
        return new UtsNameLinux(runtime);
      default:
        throw new UnsupportedOperationException("uname support only for Linux and Mac OS");
    }
  }

  private static class UtsNameMacOs extends Struct implements UtsName {
    Struct.String sysname = new Struct.AsciiString(256);
    Struct.String nodename = new Struct.AsciiString(256);
    Struct.String release = new Struct.AsciiString(256);
    Struct.String version = new Struct.AsciiString(256);
    Struct.String machine = new Struct.AsciiString(256);

    protected UtsNameMacOs(Runtime runtime) {
      super(runtime);
    }

    @Override
    public java.lang.String sysname() {
      return sysname.get();
    }

    @Override
    public java.lang.String nodename() {
      return nodename.get();
    }

    @Override
    public java.lang.String release() {
      return release.get();
    }

    @Override
    public java.lang.String version() {
      return version.get();
    }

    @Override
    public java.lang.String machine() {
      return machine.get();
    }
  }

  private static class UtsNameLinux extends Struct implements UtsName {
    Struct.String sysname = new Struct.AsciiString(65);
    Struct.String nodename = new Struct.AsciiString(65);
    Struct.String release = new Struct.AsciiString(65);
    Struct.String version = new Struct.AsciiString(65);
    Struct.String machine = new Struct.AsciiString(65);
    // we don't read it, but recent version of the linux uname syscall
    // expect space for this extra field
    Struct.String domainName = new Struct.AsciiString(65);

    protected UtsNameLinux(Runtime runtime) {
      super(runtime);
    }

    @Override
    public java.lang.String sysname() {
      return sysname.get();
    }

    @Override
    public java.lang.String nodename() {
      return nodename.get();
    }

    @Override
    public java.lang.String release() {
      return release.get();
    }

    @Override
    public java.lang.String version() {
      return version.get();
    }

    @Override
    public java.lang.String machine() {
      return machine.get();
    }
  }

  static class UtsNameToNativeConverter implements ToNativeConverter<UtsName, Pointer> {
    @Override
    public Pointer toNative(UtsName value, ToNativeContext context) {
      return Struct.getMemory((Struct) value);
    }

    @Override
    public Class<Pointer> nativeType() {
      return Pointer.class;
    }
  }

  static class UtsNameMapper implements TypeMapper {
    static final UtsNameToNativeConverter TO_NATIVE_CONVERTER = new UtsNameToNativeConverter();

    @Override
    public FromNativeConverter getFromNativeConverter(Class type) {
      return null;
    }

    @Override
    public ToNativeConverter getToNativeConverter(Class type) {
      if (UtsName.class.isAssignableFrom(type)) {
        return TO_NATIVE_CONVERTER;
      }
      return null;
    }
  }
}
