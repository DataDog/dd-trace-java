package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.protobuf.AbstractMessage;
import com.google.auto.service.AutoService;
import com.google.protobuf.UnknownFieldSet;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class AbstractMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public AbstractMessageInstrumentation() {
    super("protobuf");
  }

  @Override
  public String instrumentedType() {
    return "com.google.protobuf.UnknownFieldSet";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("writeTo")).and(takesArguments(1)),
        AbstractMessageInstrumentation.class.getName() + "$WriteToAdvice");
  }

  public static class WriteToAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void trackWriteTo(
        @Advice.This UnknownFieldSet fs) {
      System.out.println("write to called");
      // message.getDescriptorForType().getFields().forEach(field -> {
      //   final String fieldName = field.getFullName();
      //   System.out.println("Field: " + fieldName + " Type: " + field.getType().toString());
      // });
    }
  }
}
