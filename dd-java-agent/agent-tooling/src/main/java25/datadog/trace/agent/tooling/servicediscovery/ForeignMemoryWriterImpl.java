package datadog.trace.agent.tooling.servicediscovery;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.environment.OperatingSystem;
import datadog.trace.core.servicediscovery.ForeignMemoryWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ForeignMemoryWriterImpl implements ForeignMemoryWriter {
  private static final Logger log = LoggerFactory.getLogger(ForeignMemoryWriterImpl.class);

  // https://elixir.bootlin.com/linux/v6.17.1/source/include/uapi/linux/memfd.h#L8-L9
  private static final int MFD_CLOEXEC = 0x0001;
  private static final int MFD_ALLOW_SEALING = 0x0002;

  // https://elixir.bootlin.com/linux/v6.17.1/source/include/uapi/linux/fcntl.h#L40
  private static final int F_ADD_SEALS = 1033;

  // https://elixir.bootlin.com/linux/v6.17.1/source/include/uapi/linux/fcntl.h#L46-L49
  private static final int F_SEAL_SEAL = 0x0001;
  private static final int F_SEAL_SHRINK = 0x0002;
  private static final int F_SEAL_GROW = 0x0004;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();

  // Function handles - initialized once
  private static final MethodHandle SYSCALL;
  private static final MethodHandle WRITE;
  private static final MethodHandle FCNTL;

  static {
    try {
      // long syscall(long number, ...)
      // Note: variadic functions require special handling, we'll use a fixed signature
      SYSCALL =
          LINKER.downcallHandle(
              LIBC.find("syscall").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_LONG, // return type: long
                  ValueLayout.JAVA_LONG, // syscall number
                  ValueLayout.ADDRESS, // const char* name
                  ValueLayout.JAVA_INT // int flags
                  ));

      // ssize_t write(int fd, const void *buf, size_t count)
      WRITE =
          LINKER.downcallHandle(
              LIBC.find("write").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_LONG, // return type: ssize_t
                  ValueLayout.JAVA_INT, // int fd
                  ValueLayout.ADDRESS, // const void* buf
                  ValueLayout.JAVA_LONG // size_t count
                  ));

      // int fcntl(int fd, int cmd, ... /* arg */)
      FCNTL =
          LINKER.downcallHandle(
              LIBC.find("fcntl").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, // return type: int
                  ValueLayout.JAVA_INT, // int fd
                  ValueLayout.JAVA_INT, // int cmd
                  ValueLayout.JAVA_INT // int arg
                  ));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  @Override
  public void write(String fileName, byte[] payload) {
    OperatingSystem.Architecture arch = OperatingSystem.architecture();
    int memfdSyscall = getMemfdSyscall(arch);
    if (memfdSyscall <= 0) {
      log.debug(SEND_TELEMETRY, "service discovery not supported for arch={}", arch);
      return;
    }

    // Use confined arena for memory allocation during the write operation
    try (Arena arena = Arena.ofConfined()) {
      // Allocate native string for file name
      MemorySegment fileNameSegment = arena.allocateFrom(fileName);

      // Call memfd_create via syscall
      long memFd =
          (long)
              SYSCALL.invoke((long) memfdSyscall, fileNameSegment, MFD_CLOEXEC | MFD_ALLOW_SEALING);

      if (memFd < 0) {
        log.warn("{} memfd create failed, fd={}", fileName, memFd);
        return;
      }

      log.debug("{} memfd created (fd={})", fileName, memFd);

      // Allocate native memory for payload
      MemorySegment buffer = arena.allocate(payload.length);
      MemorySegment.copy(payload, 0, buffer, ValueLayout.JAVA_BYTE, 0, payload.length);

      // Write payload to memfd
      long written = (long) WRITE.invoke((int) memFd, buffer, (long) payload.length);
      if (written != payload.length) {
        log.warn(
            "write to {} memfd failed, wrote {} bytes instead of {}",
            fileName,
            written,
            payload.length);
        return;
      }

      log.debug("wrote {} bytes to memfd {}", written, memFd);

      // Add seals to prevent modification
      int returnCode =
          (int) FCNTL.invoke((int) memFd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL);

      if (returnCode == -1) {
        log.warn("failed to add seal to {} memfd", fileName);
        return;
      }

      // memfd is not closed to keep it readable for the lifetime of the process.
    } catch (Throwable t) {
      log.error("Error writing to memfd for {}", fileName, t);
    }
  }

  private static int getMemfdSyscall(OperatingSystem.Architecture arch) {
    switch (arch) {
      case X64:
        // https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_64.tbl#L331
        return 319;
      case X86:
        // https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_32.tbl#L371
        return 356;
      case ARM64:
        // https://github.com/torvalds/linux/blob/v6.17/scripts/syscall.tbl#L329
        return 279;
      case ARM:
        // https://github.com/torvalds/linux/blob/v6.17/arch/arm64/tools/syscall_32.tbl#L400
        return 385;
      default:
        return -1;
    }
  }
}
