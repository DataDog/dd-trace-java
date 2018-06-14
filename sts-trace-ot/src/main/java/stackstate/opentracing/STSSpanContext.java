package stackstate.opentracing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.opentracing.tag.Tags;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import stackstate.opentracing.decorators.AbstractDecorator;
import stackstate.trace.api.STSTags;
import stackstate.trace.common.sampling.PrioritySampling;

class STSSpanContextPidProvider implements ISTSSpanContextPidProvider {

  @Override
  public long getPid() {
    // The class ManagementFactory in the package java.lang.management provides access to the
    // "managed bean for the runtime system of the Java virtual machine".
    // The getName() method of this class is described as:
    //  Returns the name representing the running Java virtual machine.
    //  This name, as it happens, contains the process id in the Sun/Oracle JVM implementation
    // of this methods in a format such as: PID@host ,
    // Not guaranteed to work on all JVM implementations
    // Further options:
    // todo: support java 9 natively
    // https://docs.oracle.com/javase/9/docs/api/java/lang/ProcessHandle.html
    // public interface CLibrary extends Library {
    //     CLibrary INSTANCE = (CLibrary)Native.loadLibrary("c", CLibrary.class);
    //       int getpid ();
    // }

    String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    return Long.parseLong(processName.split("@")[0]);
  }
}

class STSSpanContextHostnameProvider implements ISTSSpanContextHostNameProvider {

  @Override
  public String getHostName() throws UnknownHostException {
    return InetAddress.getLocalHost().getHostName();
  }
}

/**
 * SpanContext represents Span state that must propagate to descendant Spans and across process
 * boundaries.
 *
 * <p>SpanContext is logically divided into two pieces: (1) the user-level "Baggage" that propagates
 * across Span boundaries and (2) any StackState fields that are needed to identify or contextualize
 * the associated Span instance
 */
@Slf4j
public class STSSpanContext implements io.opentracing.SpanContext {

  // Shared with other span contexts
  /** For technical reasons, the ref to the original tracer */
  private final STSTracer tracer;

  /** The collection of all span related to this one */
  private final PendingTrace trace;

  /** Baggage is associated with the whole trace and shared with other spans */
  private final Map<String, String> baggageItems;

  // Not Shared with other span contexts
  private final long traceId;
  private final long spanId;
  private final long parentId;
  private long pid = 0;
  private String hostName = "";

  /** Tags are associated to the current span, they will not propagate to the children span */
  private final Map<String, Object> tags = new ConcurrentHashMap<>();

  private ISTSSpanContextPidProvider pidProvider;
  private ISTSSpanContextHostNameProvider hostNameProvider;

  /** The service name is required, otherwise the span are dropped by the agent */
  private volatile String serviceName;
  /** The resource associated to the service (server_web, database, etc.) */
  private volatile String resourceName;
  /** Each span have an operation name describing the current span */
  private volatile String operationName;
  /** The type of the span. If null, the StackState Agent will report as a custom */
  private volatile String spanType;
  /** True indicates that the span reports an error */
  private volatile boolean errorFlag;
  /** The sampling priority of the trace */
  private volatile int samplingPriority = PrioritySampling.UNSET;
  /** When true, the samplingPriority cannot be changed. */
  private volatile boolean samplingPriorityLocked = false;

  // Additional Metadata
  private final String threadName = Thread.currentThread().getName();
  private final long threadId = Thread.currentThread().getId();

  public STSSpanContext(
      final long traceId,
      final long spanId,
      final long parentId,
      final String serviceName,
      final String operationName,
      final String resourceName,
      final int samplingPriority,
      final Map<String, String> baggageItems,
      final boolean errorFlag,
      final String spanType,
      final Map<String, Object> tags,
      final PendingTrace trace,
      final STSTracer tracer) {

    assert tracer != null;
    assert trace != null;
    this.tracer = tracer;
    this.trace = trace;

    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;

    if (baggageItems == null) {
      this.baggageItems = new ConcurrentHashMap<>(0);
    } else {
      this.baggageItems = baggageItems;
    }

    if (tags != null) {
      this.tags.putAll(tags);
    }

    this.serviceName = serviceName;
    this.operationName = operationName;
    this.resourceName = resourceName;
    this.samplingPriority = samplingPriority;
    this.errorFlag = errorFlag;
    this.spanType = spanType;

    this.pidProvider = new STSSpanContextPidProvider();
    this.hostNameProvider = new STSSpanContextHostnameProvider();
  }

  public long getTraceId() {
    return this.traceId;
  }

  public long getParentId() {
    return this.parentId;
  }

  public long getSpanId() {
    return this.spanId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(final String serviceName) {
    this.serviceName = serviceName;
  }

  public String getResourceName() {
    return this.resourceName == null || this.resourceName.isEmpty()
        ? this.operationName
        : this.resourceName;
  }

  public void setResourceName(final String resourceName) {
    this.resourceName = resourceName;
  }

  public String getOperationName() {
    return operationName;
  }

  public void setOperationName(final String operationName) {
    this.operationName = operationName;
  }

  public boolean getErrorFlag() {
    return errorFlag;
  }

  public void setErrorFlag(final boolean errorFlag) {
    this.errorFlag = errorFlag;
  }

  public String getSpanType() {
    return spanType;
  }

  public void setSpanType(final String spanType) {
    this.spanType = spanType;
  }

  public void setSamplingPriority(final int newPriority) {
    if (samplingPriorityLocked) {
      log.warn(
          "samplingPriority locked at {}. Refusing to set to {}", samplingPriority, newPriority);
    } else {
      synchronized (this) {
        // sync with lockSamplingPriority
        this.samplingPriority = newPriority;
      }
    }
  }

  public void setPidProvider(final ISTSSpanContextPidProvider provider) {
    this.pidProvider = provider;
  };

  public void setHostNameProvider(final ISTSSpanContextHostNameProvider provider) {
    this.hostNameProvider = provider;
  };

  public int getSamplingPriority() {
    return samplingPriority;
  }

  /**
   * Prevent future changes to the context's sampling priority.
   *
   * <p>Used when a span is extracted or injected for propagation.
   *
   * <p>Has no effect if the sampling priority is unset.
   *
   * @return true if the sampling priority was locked.
   */
  public boolean lockSamplingPriority() {
    if (!samplingPriorityLocked) {
      synchronized (this) {
        // sync with setSamplingPriority
        if (samplingPriority == PrioritySampling.UNSET) {
          log.debug("{} : refusing to lock unset samplingPriority", this);
        } else {
          this.samplingPriorityLocked = true;
          log.debug("{} : locked samplingPriority to {}", this, this.samplingPriority);
        }
      }
    }
    return samplingPriorityLocked;
  }

  public void setBaggageItem(final String key, final String value) {
    this.baggageItems.put(key, value);
  }

  public String getBaggageItem(final String key) {
    return this.baggageItems.get(key);
  }

  public Map<String, String> getBaggageItems() {
    return baggageItems;
  }

  /* (non-Javadoc)
   * @see io.opentracing.SpanContext#baggageItems()
   */
  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return this.baggageItems.entrySet();
  }

  @JsonIgnore
  public PendingTrace getTrace() {
    return this.trace;
  }

  @JsonIgnore
  public STSTracer getTracer() {
    return this.tracer;
  }

  /**
   * Add a tag to the span. Tags are not propagated to the children
   *
   * @param tag the tag-name
   * @param value the value of the tag. tags with null values are ignored.
   */
  public synchronized void setTag(final String tag, final Object value) {
    if (value == null) {
      tags.remove(tag);
      return;
    }

    if (tag.equals(STSTags.SERVICE_NAME)) {
      setServiceName(value.toString());
      return;
    } else if (tag.equals(STSTags.RESOURCE_NAME)) {
      setResourceName(value.toString());
      return;
    } else if (tag.equals(STSTags.SPAN_TYPE)) {
      setSpanType(value.toString());
      return;
    }

    this.tags.put(tag, value);

    // Call decorators
    final List<AbstractDecorator> decorators = tracer.getSpanContextDecorators(tag);
    if (decorators != null && value != null) {
      for (final AbstractDecorator decorator : decorators) {
        try {
          decorator.afterSetTag(this, tag, value);
        } catch (final Throwable ex) {
          log.warn(
              "Could not decorate the span decorator={}: {}",
              decorator.getClass().getSimpleName(),
              ex.getMessage());
        }
      }
    }
    // Error management
    if (Tags.ERROR.getKey().equals(tag)
        && Boolean.TRUE.equals(value instanceof String ? Boolean.valueOf((String) value) : value)) {
      this.errorFlag = true;
    }
  }

  public synchronized Map<String, Object> getTags() {
    tags.put(STSTags.THREAD_NAME, threadName);
    tags.put(STSTags.THREAD_ID, threadId);
    tags.put(STSTags.SPAN_HOSTNAME, getHostName());
    tags.put(STSTags.SPAN_PID, getPID());
    final String spanType = getSpanType();
    if (spanType != null) {
      tags.put(STSTags.SPAN_TYPE, spanType);
    }
    return Collections.unmodifiableMap(tags);
  }

  @Override
  public String toString() {
    final StringBuilder s =
        new StringBuilder()
            .append("Span [ t_id=")
            .append(traceId)
            .append(", s_id=")
            .append(spanId)
            .append(", p_id=")
            .append(parentId)
            .append("] trace=")
            .append(getServiceName())
            .append("/")
            .append(getOperationName())
            .append("/")
            .append(getResourceName());
    if (getSamplingPriority() != PrioritySampling.UNSET) {
      s.append(" samplingPriority=").append(getSamplingPriority());
    }
    if (errorFlag) {
      s.append(" *errored*");
    }
    if (tags != null) {
      s.append(" tags=").append(new TreeMap(tags));
    }
    return s.toString();
  }

  private long getPID() {
    if (this.pid == 0) {
      try {
        this.pid = this.pidProvider.getPid();
      } catch (Exception e) {
        log.debug("Failed to detect pid");
      }
    }
    return this.pid;
  }

  private String getHostName() {
    if (this.hostName.equals("")) {
      try {
        this.hostName = this.hostNameProvider.getHostName();
      } catch (Exception e) {
        log.debug("Failed to detect hostname");
      }
    }
    return this.hostName;
  }
}
