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
      System.out.println(" sending ioctl: " + String.format("%08x", UsmMessage.USM_IOCTL_ID.intValue()));
      NativeLong res = CLibrary.Instance.ioctl(new NativeLong(0), UsmMessage.USM_IOCTL_ID, Pointer.nativeValue(message.getBufferPtr()));
      System.out.println("ioctl result: " + String.format("%08x", res.intValue()));
    }
    else{
      System.out.println("INVALID MESSAGE: " + message.getClass().toString());
    }
  }

}
