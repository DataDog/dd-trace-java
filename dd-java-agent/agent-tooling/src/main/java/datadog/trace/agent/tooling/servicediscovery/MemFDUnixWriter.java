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
    int memfd_create(String name, int flags);

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
  public void write(byte[] payload) {
    final LibC libc = Native.load("c", LibC.class);

    int memFd = libc.memfd_create("datadog-tracer-info", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (memFd < 0) {
      log.warn("datadog-tracer-info memfd create failed, errno={}", Native.getLastError());
      return;
    }

    log.debug("datadog-tracer-info memfd created (fd={})", memFd);

    Memory buf = new Memory(payload.length);
    buf.write(0, payload, 0, payload.length);

    NativeLong written = libc.write(memFd, buf, new NativeLong(payload.length));
    if (written.longValue() != payload.length) {
      log.warn("write to datadog-tracer-info memfd failed errno={}", Native.getLastError());
      return;
    }
    log.debug("wrote {} bytes to memfd {}", written.longValue(), memFd);
    int returnCode = libc.fcntl(memFd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL);
    if (returnCode == -1) {
      log.warn("failed to add seal to datadog-tracer-info memfd errno={}", Native.getLastError());
      return;
    }
    // memfd is not closed to keep it readable for the lifetime of the process.
  }
}
