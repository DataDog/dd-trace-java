package datadog.trace.instrumentation.sslsocketimpl;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.usmextractor.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.usmextractor.UsmMessage;
import net.bytebuddy.asm.Advice;
import sun.security.ssl.SSLSocketImpl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public class SslSocketImplStreamsInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes {

  public SslSocketImplStreamsInstrumentation() {
    super("sun-sslsocketimpl-streams","sslsocketimpl-streams","sslsocket-streams");
  }

  @Override
  public String[] knownMatchingTypes() {
    // we instrument both input and output streams which are inner classes of the SSLSocketImpl
    return new String[] {
      "sun.security.ssl.SSLSocketImpl$AppInputStream", "sun.security.ssl.SSLSocketImpl$AppOutputStream"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("write")
            .and(takesArguments(3))
            .and(takesArgument(0,byte[].class))
            .and(takesArgument(1,int.class))
            .and(takesArgument(2,int.class))),
        SslSocketImplStreamsInstrumentation.class.getName() + "$WriteAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("read")
                .and(takesArguments(3))
                .and(takesArgument(0,byte[].class))
                .and(takesArgument(1,int.class))
                .and(takesArgument(2,int.class))),
        SslSocketImplStreamsInstrumentation.class.getName() + "$ReadAdvice");
  }

  public static class WriteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void write(
        @Advice.FieldValue("this$0") SSLSocketImpl socket,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) int offset,
        @Advice.Argument(2) int len)
    {

      System.out.println("Output Stream write:");
      UsmMessage message = new UsmMessage.RequestUsmMessage(socket, buffer, offset, len);
      UsmExtractor.send(message);
      System.out.println("src host: " + socket.getLocalSocketAddress().toString() + " src port: " + socket.getLocalPort());
      System.out.println("dst host: " + socket.getRemoteSocketAddress().toString() + " dst port: " + socket.getPeerPort());
      System.out.println("intercepted write, byte len: " + len);
    }

  }

  public static class ReadAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void read(
        @Advice.FieldValue("this$0") SSLSocketImpl socket,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) int offset,
        @Advice.Argument(2) int len)
    {

      System.out.println("Input Stream read:");
      UsmMessage message = new UsmMessage.RequestUsmMessage(socket, buffer, offset, len);
      UsmExtractor.send(message);
      System.out.println("src host: " + socket.getLocalSocketAddress().toString() + " src port: " + socket.getLocalPort());
      System.out.println("dst host: " + socket.getRemoteSocketAddress().toString() + " dst port: " + socket.getPeerPort());
      System.out.println("intercepted write, byte len: " + len);
    }

  }
}
