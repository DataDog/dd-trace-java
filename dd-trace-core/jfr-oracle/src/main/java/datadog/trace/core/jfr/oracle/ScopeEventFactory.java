package datadog.trace.core.jfr.oracle;

import com.oracle.jrockit.jfr.EventToken;
import com.oracle.jrockit.jfr.InstantEvent;
import com.oracle.jrockit.jfr.InvalidEventDefinitionException;
import com.oracle.jrockit.jfr.InvalidValueException;
import com.oracle.jrockit.jfr.Producer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.jfr.DDNoopScopeEvent;
import datadog.trace.core.jfr.DDScopeEvent;
import datadog.trace.core.jfr.DDScopeEventFactory;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements DDScopeEventFactory {
  private static final Logger log = LoggerFactory.getLogger(ScopeEventFactory.class);

  private static final Producer PRODUCER;
  private static final EventToken SCOPE_EVENT_TOKEN;

  static {
    URI producerURI = URI.create("http://datadoghq.com/jfr-tracer");
    PRODUCER =
        new Producer("jfr-tracer", "Events produced by the DataDog jfr-tracer.", producerURI);
    PRODUCER.register();
    Class<? extends InstantEvent> eventClass;
    try {
      eventClass =
          (Class<? extends InstantEvent>)
              ScopeEventFactory.class
                  .getClassLoader()
                  .loadClass("datadog.trace.core.jfr.oracle.ScopeEvent");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    SCOPE_EVENT_TOKEN = register(eventClass);
  }

  @Override
  public DDScopeEvent create(final AgentSpan.Context context) {
    return SCOPE_EVENT_TOKEN.isEnabled() && context instanceof DDSpanContext
        ? new ScopeEvent(SCOPE_EVENT_TOKEN, (DDSpanContext) context)
        : DDNoopScopeEvent.INSTANCE;
  }

  /**
   * Helper method to register an event class with the jfr-tracer producer.
   *
   * @param clazz the event class to register.
   * @return the token associated with the event class.
   * @throws IllegalStateException when the event can not be registered
   */
  static EventToken register(Class<? extends InstantEvent> clazz) {
    try {
      EventToken token = PRODUCER.addEvent(clazz);
      log.debug("Registered EventType {}", clazz.getName());
      return token;
    } catch (InvalidEventDefinitionException | InvalidValueException e) {
      log.debug(
          "Failed to register the event class {}. Event will not be available. Please check your configuration.",
          clazz.getName(),
          e);
      throw new RuntimeException(e);
    }
  }
}
