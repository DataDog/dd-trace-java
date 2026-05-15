package datadog.trace.agent.tooling.servicediscovery;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

public final class MemFDUnixWriterJNA extends MemFDUnixWriter {
  private static final class Lazy {
    static final LibC LIBC = Native.load("c", LibC.class);
  }

  private interface LibC extends Library {
    long syscall(long number, Object... args);

    NativeLong write(int fd, Pointer buf, NativeLong count);

    int fcntl(int fd, int cmd, int arg);
  }

  @Override
  protected long syscall(long number, String name, int flags) {
    return Lazy.LIBC.syscall(number, name, flags);
  }

  @Override
  protected long write(int fd, byte[] payload) {
    Memory buf = new Memory(payload.length);
    buf.write(0, payload, 0, payload.length);
    return Lazy.LIBC.write(fd, buf, new NativeLong(payload.length)).longValue();
  }

  @Override
  protected int fcntl(int fd, int cmd, int arg) {
    return Lazy.LIBC.fcntl(fd, cmd, arg);
  }

  @Override
  protected int getLastError() {
    return Native.getLastError();
  }
}
