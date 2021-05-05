package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.HandlerContextKey;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class RequestInstrumentation extends Instrumenter.Tracing {

  public RequestInstrumentation() {
    super("aws-sdk");
  }

  static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("com.amazonaws.AmazonWebServiceRequest");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return nameStartsWith("com.amazonaws.services.")
        .and(extendsClass(named("com.amazonaws.AmazonWebServiceRequest")));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("setBucketName").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$BucketNameAdvice");
    transformation.applyAdvice(
        named("setQueueUrl").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$QueueUrlAdvice");
    transformation.applyAdvice(
        named("setQueueName").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$QueueNameAdvice");
    transformation.applyAdvice(
        named("setStreamName").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$StreamNameAdvice");
    transformation.applyAdvice(
        named("setTableName").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$TableNameAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.amazonaws.AmazonWebServiceRequest", Map.class.getName());
  }

  public static class BucketNameAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(0) final String value,
        @Advice.This final AmazonWebServiceRequest request) {
      final ContextStore<AmazonWebServiceRequest, Map> contextStore =
          InstrumentationContext.get(AmazonWebServiceRequest.class, Map.class);
      Map<String, String> requestMeta = contextStore.get(request);
      if (requestMeta == null) {
        requestMeta = new HashMap<>(8);
        contextStore.put(request, requestMeta);
      }
      requestMeta.put("aws.bucket.name", value);
    }

    // Don't apply this advice when HandlerContextKey is missing as we need it to trace requests
    private static void muzzleCheck() {
      new HandlerContextKey<>("muzzle");
    }
  }

  public static class QueueUrlAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(0) final String value,
        @Advice.This final AmazonWebServiceRequest request) {
      final ContextStore<AmazonWebServiceRequest, Map> contextStore =
          InstrumentationContext.get(AmazonWebServiceRequest.class, Map.class);
      Map<String, String> requestMeta = contextStore.get(request);
      if (requestMeta == null) {
        requestMeta = new HashMap<>(8);
        contextStore.put(request, requestMeta);
      }
      requestMeta.put("aws.queue.url", value);
    }

    // Don't apply this advice when HandlerContextKey is missing as we need it to trace requests
    private static void muzzleCheck() {
      new HandlerContextKey<>("muzzle");
    }
  }

  public static class QueueNameAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(0) final String value,
        @Advice.This final AmazonWebServiceRequest request) {
      final ContextStore<AmazonWebServiceRequest, Map> contextStore =
          InstrumentationContext.get(AmazonWebServiceRequest.class, Map.class);
      Map<String, String> requestMeta = contextStore.get(request);
      if (requestMeta == null) {
        requestMeta = new HashMap<>(8);
        contextStore.put(request, requestMeta);
      }
      requestMeta.put("aws.queue.name", value);
    }

    // Don't apply this advice when HandlerContextKey is missing as we need it to trace requests
    private static void muzzleCheck() {
      new HandlerContextKey<>("muzzle");
    }
  }

  public static class StreamNameAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(0) final String value,
        @Advice.This final AmazonWebServiceRequest request) {
      final ContextStore<AmazonWebServiceRequest, Map> contextStore =
          InstrumentationContext.get(AmazonWebServiceRequest.class, Map.class);
      Map<String, String> requestMeta = contextStore.get(request);
      if (requestMeta == null) {
        requestMeta = new HashMap<>(8);
        contextStore.put(request, requestMeta);
      }
      requestMeta.put("aws.stream.name", value);
    }

    // Don't apply this advice when HandlerContextKey is missing as we need it to trace requests
    private static void muzzleCheck() {
      new HandlerContextKey<>("muzzle");
    }
  }

  public static class TableNameAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.Argument(0) final String value,
        @Advice.This final AmazonWebServiceRequest request) {
      final ContextStore<AmazonWebServiceRequest, Map> contextStore =
          InstrumentationContext.get(AmazonWebServiceRequest.class, Map.class);
      Map<String, String> requestMeta = contextStore.get(request);
      if (requestMeta == null) {
        requestMeta = new HashMap<>(8);
        contextStore.put(request, requestMeta);
      }
      requestMeta.put("aws.table.name", value);
    }

    // Don't apply this advice when HandlerContextKey is missing as we need it to trace requests
    private static void muzzleCheck() {
      new HandlerContextKey<>("muzzle");
    }
  }
}
