package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.cluster.Cluster;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.cluster.Partition;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class AerospikeClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Node> {
  public static final UTF8BytesString AEROSPIKE_JAVA =
      UTF8BytesString.createConstant("aerospike-java");

  public static final AerospikeClientDecorator DECORATE = new AerospikeClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aerospike"};
  }

  @Override
  protected String service() {
    return "aerospike";
  }

  @Override
  protected CharSequence component() {
    return "java-aerospike";
  }

  @Override
  protected CharSequence spanType() {
    return DDSpanTypes.AEROSPIKE;
  }

  @Override
  protected String dbType() {
    return "aerospike";
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

    final InetSocketAddress socketAddress = node.getAddress();
    span.setTag(Tags.PEER_HOSTNAME, socketAddress.getHostName());
    span.setTag(Tags.PEER_PORT, socketAddress.getPort());
    final InetAddress remoteAddress = socketAddress.getAddress();
    if (remoteAddress instanceof Inet4Address) {
      span.setTag(Tags.PEER_HOST_IPV4, remoteAddress.getHostAddress());
    } else if (remoteAddress instanceof Inet6Address) {
      span.setTag(Tags.PEER_HOST_IPV6, remoteAddress.getHostAddress());
    }

    if (cluster != null && cluster.getUser() != null) {
      span.setTag(Tags.DB_USER, new String(cluster.getUser(), StandardCharsets.UTF_8));
    }

    if (partition != null) {
      String instanceName = partition.toString();
      final int namespaceEnd = instanceName.indexOf(':');
      if (namespaceEnd > 0) {
        instanceName = instanceName.substring(0, namespaceEnd);
      }
      span.setTag(Tags.DB_INSTANCE, instanceName);
      if (instanceName != null && Config.get().isDbClientSplitByInstance()) {
        span.setTag(DDTags.SERVICE_NAME, instanceName);
      }
    }

    return span;
  }

  public void withMethod(final AgentSpan span, final String methodName) {
    span.setTag(DDTags.RESOURCE_NAME, spanNameForMethod(AerospikeClient.class, methodName));
  }

  public AgentScope startAerospikeSpan(final String methodName) {
    final AgentSpan span = startSpan(AEROSPIKE_JAVA);
    afterStart(span);
    withMethod(span, methodName);
    return activateSpan(span);
  }

  public void finishAerospikeSpan(final AgentScope scope, final Throwable error) {
    if (scope != null) {
      final AgentSpan span = scope.span();
      if (error != null) {
        onError(span, error);
      }
      beforeFinish(span);
      span.finish();
      scope.close();
    }
  }
}
