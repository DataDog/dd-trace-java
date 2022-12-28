package datadog.trace.bootstrap.instrumentation.usmextractor;

import sun.security.ssl.SSLSocketImpl;
import com.sun.jna.Library;
import com.sun.jna.NativeLong;
import com.sun.jna.Native;
import com.sun.jna.Pointer;


//TODO: maybe move to a separate module: "agent-usm" (similar to agent-iast, agent-debugger, etc... under the dd-java-agent)
public class UsmExtractor {
  public interface CLibrary extends Library {
    CLibrary Instance = (CLibrary) Native.load("c", CLibrary.class);

    NativeLong ioctl(NativeLong fd, NativeLong request, Object... args);
  }

  public static void send(UsmMessage message) {
    if (message.validate()){
      NativeLong res = CLibrary.Instance.ioctl(new NativeLong(0), UsmMessage.USM_IOCTL_ID, Pointer.nativeValue(message.getBufferPtr()));
    }
  }

}
