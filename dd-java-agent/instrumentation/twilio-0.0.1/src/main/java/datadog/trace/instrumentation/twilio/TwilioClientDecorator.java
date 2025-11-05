package datadog.trace.instrumentation.twilio;

import com.google.common.util.concurrent.ListenableFuture;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Message;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decorate Twilio span's with relevant contextual information. */
public class TwilioClientDecorator extends ClientDecorator {

  private static final Logger log = LoggerFactory.getLogger(TwilioClientDecorator.class);

  public static final CharSequence TWILIO_SDK = UTF8BytesString.create("twilio.sdk");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("twilio-sdk");

  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().allowInferredServices()
          ? COMPONENT_NAME.toString()
          : null;

  private static final QualifiedClassNameCache NAMES =
      new QualifiedClassNameCache(
          new Function<Class<?>, CharSequence>() {
            @Override
            // Drop common package prefix (com.twilio.rest)
            public String apply(Class<?> input) {
              return input.getCanonicalName().substring("com.twilio.rest.".length());
            }
          },
          Functions.PrefixJoin.of("."));

  public static final TwilioClientDecorator DECORATE = new TwilioClientDecorator();

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {COMPONENT_NAME.toString()};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  /** Decorate trace based on service execution metadata. */
  public AgentSpan onServiceExecution(
      final AgentSpan span, final Object serviceExecutor, final String methodName) {
    span.setResourceName(NAMES.getQualifiedName(serviceExecutor.getClass(), methodName));
    return span;
  }

  /** Annotate the span with the results of the operation. */
  public AgentSpan onResult(final AgentSpan span, Object result) {

    // Unwrap ListenableFuture (if present)
    if (result instanceof ListenableFuture) {
      try {
        result = ((ListenableFuture) result).get(0, TimeUnit.MICROSECONDS);
      } catch (final Exception e) {
        log.debug("Error unwrapping result", e);
      }
    }

    // Nothing to do here, so return
    if (result == null) {
      return span;
    }

    // Provide helpful metadata for some of the more common response types
    span.setTag("twilio.type", result.getClass().getCanonicalName());

    // Instrument the most popular resource types directly
    if (result instanceof Message) {
      final Message message = (Message) result;
      span.setTag("twilio.account", message.getAccountSid());
      span.setTag("twilio.sid", message.getSid());
      if (message.getStatus() != null) {
        span.setTag("twilio.status", message.getStatus().toString());
      }
    } else if (result instanceof Call) {
      final Call call = (Call) result;
      span.setTag("twilio.account", call.getAccountSid());
      span.setTag("twilio.sid", call.getSid());
      span.setTag("twilio.parentSid", call.getParentCallSid());
      if (call.getStatus() != null) {
        span.setTag("twilio.status", call.getStatus().toString());
      }
    } else {
      // Use reflection to gather insight from other types; note that Twilio requests take close to
      // 1 second, so the added hit from reflection here is relatively minimal in the grand scheme
      // of things
      setTagIfPresent(span, result, "twilio.sid", "getSid");
      setTagIfPresent(span, result, "twilio.account", "getAccountSid");
      setTagIfPresent(span, result, "twilio.status", "getStatus");
    }

    return span;
  }

  /**
   * Helper method for calling a getter using reflection. This will be slow, so only use when
   * required.
   */
  private void setTagIfPresent(
      final AgentSpan span, final Object result, final String tag, final String getter) {
    try {
      final Method method = result.getClass().getMethod(getter);
      final Object value = method.invoke(result);

      if (value != null) {
        span.setTag(tag, value.toString());
      }

    } catch (final Exception e) {
      // Expected that this won't work for all result types
    }
  }
}
