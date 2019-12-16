package datadog.trace.instrumentation.aws.v0;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation might work with versions before 1.11.0, but this was the first version that
 * is tested. It could possibly be extended earlier.
 */
@AutoService(Instrumenter.class)
public final class AWSKinesisClientInstrumentation extends Instrumenter.Default {

  public AWSKinesisClientInstrumentation() {
    super("aws-sdk");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // TODO: What should this matching clause be
    return named("com.amazonaws.services.kinesis.AmazonKinesisClient");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isConstructor(), AWSKinesisClientInstrumentation.class.getName() + "$KinesisClientAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.decorator.BaseDecorator",
      "datadog.trace.agent.decorator.ClientDecorator",
      "datadog.trace.agent.decorator.HttpClientDecorator",
      packageName + ".AwsSdkClientDecorator",
      packageName + ".KinesisClientDecorator",
      packageName + ".TracingRequestHandler",
      packageName + ".KinesisTracingRequestHandler",
    };
  }

  public static class KinesisClientAdvice {
    // Since we're instrumenting the constructor, we can't add onThrowable.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(
        @Advice.FieldValue("requestHandler2s") final List<RequestHandler2> handlers) {

      // DQH - This is a bit ugly but effective.

      // Our general instrumentation adds a generic TracingRequestHandler,
      // but for Kinesis we'd like extra tags so we want to use a
      // KinesisTracingRequestHandler instead.

      // To avoid double spans, we need to remove any existing TracingRequestHandler-s
      // and use a KinesisTracingRequestHandler instead.  In practice, the generic
      // instrumentation guarantees that we only have a single TracingRequestHandler
      // after the super constructor completes, so this code only handles a single
      // TracingRequestHandler being in the list.

      // As a final wrinkle, the List is a CopyOnWriteArrayList and its
      // ListIterator doesn't support the remove operation.  So we have
      // to store the index and manipulate the list after the loop.

      boolean addedKinesisDDHandler = false;
      int tracingRequestHandlerIndex = -1;
      ListIterator<RequestHandler2> handlerIter = handlers.listIterator();
      while (handlerIter.hasNext()) {
        int index = handlerIter.nextIndex();
        RequestHandler2 handler2 = handlerIter.next();

        if (handler2 instanceof KinesisTracingRequestHandler) {
          addedKinesisDDHandler = true;
        } else if (handler2 instanceof TracingRequestHandler) {
          tracingRequestHandlerIndex = index;
        }
      }

      if (tracingRequestHandlerIndex == -1) {
        if (!addedKinesisDDHandler) {
          handlers.add(KinesisTracingRequestHandler.INSTANCE);
        }
      } else {
        if (addedKinesisDDHandler) {
          handlers.remove(tracingRequestHandlerIndex);
        } else {
          handlers.set(tracingRequestHandlerIndex, KinesisTracingRequestHandler.INSTANCE);
        }
      }
    }
  }
}
