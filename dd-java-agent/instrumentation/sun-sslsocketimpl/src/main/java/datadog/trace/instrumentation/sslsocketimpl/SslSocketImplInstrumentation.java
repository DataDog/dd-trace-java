package datadog.trace.instrumentation.sslsocketimpl;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import sun.security.ssl.SSLSocketImpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public class SslSocketImplInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public SslSocketImplInstrumentation() {
    super("sun-sslsocketimpl","sslsocketimpl","sslsocket");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("close")
                .and(takesArguments(0))),
        SslSocketImplInstrumentation.class.getName() + "$CloseAdvice");
  }

  @Override
  public String instrumentedType() {
    return "sun.security.ssl.SSLSocketImpl";
  }

  public static class CloseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(
        @Advice.This SSLSocketImpl socket
    ){

      System.out.println("close socket:");
      System.out.println("src host: " + socket.getLocalSocketAddress().toString() + " src port: " + socket.getLocalPort());
      System.out.println("dst host: " + socket.getRemoteSocketAddress().toString() + " dst port: " + socket.getPeerPort());
    }
  }
}
