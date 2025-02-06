package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import org.springframework.core.io.buffer.DataBuffer;

/** @see DataBuffer#asInputStream() */
@AutoService(InstrumenterModule.class)
public class DataBufferInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public DataBufferInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.springframework.core.io.buffer.DataBuffer", // asInputStream is default interf method in
      // flux6
      "org.springframework.core.io.buffer.DefaultDataBuffer",
      "org.springframework.core.io.buffer.NettyDataBuffer",
      "org.springframework.http.server.reactive.UndertowServerHttpRequest$UndertowDataBuffer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("asInputStream")).and(takesArguments(0)),
        packageName + ".DataBufferAsInputStreamAdvice");
  }
}
