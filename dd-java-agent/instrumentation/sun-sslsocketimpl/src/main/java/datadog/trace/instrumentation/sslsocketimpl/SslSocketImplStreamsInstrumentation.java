package datadog.trace.instrumentation.sslsocketimpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.io.InputStream;
import java.io.OutputStream;
import javax.net.ssl.SSLSocket;

import datadog.trace.bootstrap.instrumentation.sslsocket.UsmFilterInputStream;
import datadog.trace.bootstrap.instrumentation.sslsocket.UsmFilterOutputStream;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public final class SslSocketImplStreamsInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  private static final Logger log =
      LoggerFactory.getLogger(SslSocketImplStreamsInstrumentation.class);

  public SslSocketImplStreamsInstrumentation() {
    super("sun-sslsocketimpl-streams", "sslsocketimpl-streams", "sslsocket-streams");
  }

  @Override
  public String hierarchyMarkerType() {
    // for bootclass loader
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.extendsClass(named("javax.net.ssl.SSLSocket"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("getInputStream")),
        SslSocketImplStreamsInstrumentation.class.getName() + "$GetInputStreamAdvice");
    transformation.applyAdvice(
        isMethod().and(named("getOutputStream")),
        SslSocketImplStreamsInstrumentation.class.getName() + "$GetOutputStreamAdvice");
  }

  public static final class GetOutputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getOutputStream(
        @Advice.This final SSLSocket socket,
        @Advice.Return(readOnly = false) OutputStream retValue) {
      retValue = new UsmFilterOutputStream(retValue, socket);
    }
  }

  public static final class GetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getInputStream(
        @Advice.This final SSLSocket socket,
        @Advice.Return(readOnly = false) InputStream retValue) {
      retValue = new UsmFilterInputStream(retValue, socket);
    }
  }
}
