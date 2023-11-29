package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.cluster.Cluster;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.cluster.Partition;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class AerospikeClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Node> {
  private static final String DB_TYPE = "aerospike";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);
  public static final UTF8BytesString JAVA_AEROSPIKE = UTF8BytesString.create("java-aerospike");
  public static final UTF8BytesString OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));

  public static final AerospikeClientDecorator DECORATE = new AerospikeClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aerospike"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return JAVA_AEROSPIKE;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.AEROSPIKE;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
  }

  @Override
  protected String dbUser(final Node node) {
    return null;
  }

  @Override
  protected String dbInstance(final Node node) {
    return null;
  }

  @Override
  protected String dbHostname(final Node node) {
    return null;
  }

  public AgentSpan onConnection(
      final AgentSpan span, final Node node, final Cluster cluster, final Partition partition) {

    onPeerConnection(span, node.getAddress());

    if (cluster != null && cluster.getUser() != null) {
      span.setTag(Tags.DB_USER, UTF8BytesString.create(cluster.getUser()));
    }

    if (partition != null) {
      String instanceName = partition.toString();
      final int namespaceEnd = instanceName.indexOf(':');
      if (namespaceEnd > 0) {
        instanceName = instanceName.substring(0, namespaceEnd);
      }
      span.setTag(Tags.DB_INSTANCE, instanceName);
      if (Config.get().isDbClientSplitByInstance()) {
        span.setServiceName(instanceName);
      }
    }

    return span;
  }

  public void withMethod(final AgentSpan span, final String methodName) {
    span.setResourceName(spanNameForMethod(AerospikeClient.class, methodName));
  }

  public AgentSpan startAerospikeSpan(final String methodName) {
    final AgentSpan span = startSpan(OPERATION_NAME);
    afterStart(span);
    withMethod(span, methodName);
    return span;
  }

  public void finishAerospikeSpan(final AgentSpan span, final Throwable error) {
    if (error != null) {
      onError(span, error);
    }
    beforeFinish(span);
    span.finish();
  }
}
