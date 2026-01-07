package datadog.trace.agent.tooling.servicediscovery;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemFDUnixWriterFFM extends MemFDUnixWriter {
  private static final Logger log = LoggerFactory.getLogger(MemFDUnixWriterFFM.class);

  // Captured call state layout for errno
  private static final StructLayout CAPTURE_STATE_LAYOUT = Linker.Option.captureStateLayout();
  private static final long ERRNO_OFFSET =
      CAPTURE_STATE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("errno"));

  // Function handles - initialized once
  private final MethodHandle syscallMH;
  private final MethodHandle writeMH;
  private final MethodHandle fcntlMH;

  private final MemorySegment captureState;

  public MemFDUnixWriterFFM() {
    final Linker linker = Linker.nativeLinker();
    final SymbolLookup LIBC = linker.defaultLookup();

    // Allocate memory for capturing errno (need to be alive until the class instance is collected)
    this.captureState = Arena.ofAuto().allocate(CAPTURE_STATE_LAYOUT);

    // long syscall(long number, ...)
    // Note: variadic functions require special handling, we'll use a fixed signature
    syscallMH =
        linker.downcallHandle(
            LIBC.find("syscall").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG, // return type: long
                ValueLayout.JAVA_LONG, // syscall number
                ValueLayout.ADDRESS, // const char* name
                ValueLayout.JAVA_INT // int flags
                ),
            Linker.Option.captureCallState("errno"));

    // ssize_t write(int fd, const void *buf, size_t count)
    writeMH =
        linker.downcallHandle(
            LIBC.find("write").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG, // return type: ssize_t
                ValueLayout.JAVA_INT, // int fd
                ValueLayout.ADDRESS, // const void* buf
                ValueLayout.JAVA_LONG // size_t count
                ),
            Linker.Option.captureCallState("errno"));

    // int fcntl(int fd, int cmd, ... /* arg */)
    fcntlMH =
        linker.downcallHandle(
            LIBC.find("fcntl").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, // return type: int
                ValueLayout.JAVA_INT, // int fd
                ValueLayout.JAVA_INT, // int cmd
                ValueLayout.JAVA_INT // int arg
                ),
            Linker.Option.captureCallState("errno"));
  }

  @Override
  protected long syscall(long number, String name, int flags) {
    try (Arena arena = Arena.ofConfined()) {
      // Allocate native string for file name
      MemorySegment fileNameSegment = arena.allocateFrom(name);
      // Call memfd_create via syscall, passing captureState as first arg
      return (long) syscallMH.invoke(captureState, (long) number, fileNameSegment, flags);
    } catch (Throwable t) {
      log.error("Unable to make a syscall through FFM", t);
      return -1;
    }
  }

  @Override
  protected long write(int fd, byte[] payload) {
    try (Arena arena = Arena.ofConfined()) {
      // Allocate native memory for payload
      MemorySegment buffer = arena.allocate(payload.length);
      MemorySegment.copy(payload, 0, buffer, ValueLayout.JAVA_BYTE, 0, payload.length);

      // Write payload to memfd, passing captureState as first arg
      return (long) writeMH.invoke(captureState, fd, buffer, (long) payload.length);
    } catch (Throwable t) {
      log.error("Unable to make a write call through FFM", t);
      return -1;
    }
  }

  @Override
  protected int fcntl(int fd, int cmd, int arg) {
    try {
      return (int) fcntlMH.invoke(captureState, fd, cmd, arg);
    } catch (Throwable t) {
      log.error("Unable to make a fcntl call through FFM", t);
      return -1;
    }
  }

  @Override
  protected int getLastError() {
    try {
      // Read errno from the captured state memory segment
      return captureState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
    } catch (Throwable t) {
      log.error("Unable to read errno from captured state", t);
      return -1;
    }
  }
}
