package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.cluster.Node;
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

  @Override
  public AgentSpan onConnection(final AgentSpan span, final Node node) {
    span.setTag(Tags.PEER_HOSTNAME, node.getHost().name);
    span.setTag(Tags.PEER_PORT, node.getHost().port);
    final InetAddress remoteAddress = node.getAddress().getAddress();
    if (remoteAddress instanceof Inet4Address) {
      span.setTag(Tags.PEER_HOST_IPV4, remoteAddress.getHostAddress());
    } else if (remoteAddress instanceof Inet6Address) {
      span.setTag(Tags.PEER_HOST_IPV6, remoteAddress.getHostAddress());
    }
    return super.onConnection(span, node);
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
