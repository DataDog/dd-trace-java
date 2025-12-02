package datadog.trace.instrumentation.liberty20;

import com.ibm.websphere.servlet.request.IRequest;
import com.ibm.websphere.servlet.request.extended.IRequestExtended;
import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import com.ibm.wsspi.http.HttpRequest;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

public class RequestMessageFromServletRequestHelper {
  private static final ConcurrentHashMap<Class<?>, MethodHandle> FIELD_CACHE =
      new ConcurrentHashMap<>();

  public static Object getHttpRequestMessage(SRTServletRequest request) {
    IRequest iRequest = request.getIRequest();
    if (iRequest instanceof IRequestExtended) {
      HttpRequest transportRequest =
          ((IRequestExtended) iRequest).getHttpInboundConnection().getRequest();
      try {
        MethodHandle messageGetter =
            FIELD_CACHE.computeIfAbsent(
                transportRequest.getClass(),
                clazz -> {
                  Field messageField;
                  try {
                    messageField = clazz.getDeclaredField("message");
                    messageField.setAccessible(true);
                    return MethodHandles.lookup().unreflectGetter(messageField);
                  } catch (NoSuchFieldException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                  }
                });
        return messageGetter.invoke(transportRequest);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
    return null;
  }
}
