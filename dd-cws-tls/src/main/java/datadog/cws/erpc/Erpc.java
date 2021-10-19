package datadog.cws.erpc;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * This class is use the send CWS eRPC request.
 *
 * <p>It used an ioctl syscall with a dedicated request code that can be handled by the CWS eBPF
 * code.
 */
public class Erpc {
  static final NativeLong REQUEST_CODE = new NativeLong(0xdeadc001L);

  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong ioctl(NativeLong fd, NativeLong request, Object... args);
  }

  public static void send(Request request) {
    CLibrary.Instance.ioctl(new NativeLong(0), REQUEST_CODE, Pointer.nativeValue(request.pointer));
  }
}
