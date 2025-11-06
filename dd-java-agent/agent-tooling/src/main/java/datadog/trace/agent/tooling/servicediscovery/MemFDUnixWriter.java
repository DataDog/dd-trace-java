package datadog.trace.agent.tooling.servicediscovery;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import datadog.trace.core.servicediscovery.ForeignMemoryWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemFDUnixWriter implements ForeignMemoryWriter {
  private static final Logger log = LoggerFactory.getLogger(MemFDUnixWriter.class);

  private interface LibC extends Library {
    int syscall(int number, Object... args);

    NativeLong write(int fd, Pointer buf, NativeLong count);

    int fcntl(int fd, int cmd, int arg);
  }

  // https://elixir.bootlin.com/linux/v6.17.1/source/include/uapi/linux/memfd.h#L8-L9
  private static final int MFD_CLOEXEC = 0x0001;
  private static final int MFD_ALLOW_SEALING = 0x0002;

  // https://elixir.bootlin.com/linux/v6.17.1/source/include/uapi/linux/fcntl.h#L40
  private static final int F_ADD_SEALS = 1033; //

  // https://elixir.bootlin.com/linux/v6.17.1/source/include/uapi/linux/fcntl.h#L46-L49
  private static final int F_SEAL_SEAL = 0x0001;
  private static final int F_SEAL_SHRINK = 0x0002;
  private static final int F_SEAL_GROW = 0x0004;

  @Override
  public void write(String fileName, byte[] payload) {
    final LibC libc = Native.load("c", LibC.class);

    String arch = System.getProperty("os.arch");
    int memfdSyscall = getMemfdSyscall(arch);
    if (memfdSyscall <= 0) {
      log.debug("service discovery not supported for arch={}", arch);
      return;
    }
    int memFd = libc.syscall(memfdSyscall, fileName, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (memFd < 0) {
      log.warn("{} memfd create failed, errno={}", fileName, Native.getLastError());
      return;
    }

    log.debug("{} memfd created (fd={})", fileName, memFd);

    Memory buf = new Memory(payload.length);
    buf.write(0, payload, 0, payload.length);

    NativeLong written = libc.write(memFd, buf, new NativeLong(payload.length));
    if (written.longValue() != payload.length) {
      log.warn("write to {} memfd failed errno={}", fileName, Native.getLastError());
      return;
    }
    log.debug("wrote {} bytes to memfd {}", written.longValue(), memFd);
    int returnCode = libc.fcntl(memFd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL);
    if (returnCode == -1) {
      log.warn("failed to add seal to {} memfd errno={}", fileName, Native.getLastError());
      return;
    }
    // memfd is not closed to keep it readable for the lifetime of the process.
  }

  private static int getMemfdSyscall(String arch) {
    switch (arch.toLowerCase()) {
        // https://elixir.bootlin.com/musl/v1.2.5/source/arch/x86_64/bits/syscall.h.in#L320
      case "x86_64":
        return 319;
      case "x64":
        return 319;
      case "amd64":
        return 319;
        // https://elixir.bootlin.com/musl/v1.2.5/source/arch/i386/bits/syscall.h.in#L356
      case "x386":
        return 356;
      case "86":
        return 356;
        // https://elixir.bootlin.com/musl/v1.2.5/source/arch/aarch64/bits/syscall.h.in#L264
      case "aarch64":
        return 279;
      case "arm64":
        return 279;
        // https://elixir.bootlin.com/musl/v1.2.5/source/arch/arm/bits/syscall.h.in#L343
      case "arm":
        return 385;
      case "arm32":
        return 385;
        // https://elixir.bootlin.com/musl/v1.2.5/source/arch/powerpc64/bits/syscall.h.in#L350
      case "ppc64":
        return 360;
      default:
        return -1;
    }
  }
}
