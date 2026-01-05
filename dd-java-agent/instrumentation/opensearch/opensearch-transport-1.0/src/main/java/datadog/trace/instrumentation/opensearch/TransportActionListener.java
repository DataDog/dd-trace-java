package datadog.trace.instrumentation.opensearch;

import static datadog.trace.instrumentation.opensearch.OpensearchTransportClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.opensearch.action.ActionListener;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionResponse;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.IndicesRequest;
import org.opensearch.action.bulk.BulkShardResponse;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.broadcast.BroadcastResponse;
import org.opensearch.action.support.nodes.BaseNodesResponse;
import org.opensearch.action.support.replication.ReplicationResponse;

public class TransportActionListener<T extends ActionResponse> implements ActionListener<T> {

  private final ActionListener<T> listener;
  private final AgentSpan span;

  public TransportActionListener(
      final ActionRequest actionRequest, final ActionListener<T> listener, final AgentSpan span) {
    this.listener = listener;
    this.span = span;
    onRequest(actionRequest);
  }

  private void onRequest(final ActionRequest request) {
    if (request instanceof IndicesRequest) {
      final IndicesRequest req = (IndicesRequest) request;
      if (req.indices() != null) {
        span.setTag("opensearch.request.indices", String.join(",", req.indices()));
      }
    }
    if (request instanceof DocWriteRequest) {
      final DocWriteRequest req = (DocWriteRequest) request;
      span.setTag("opensearch.request.write.routing", req.routing());
      span.setTag("opensearch.request.write.version", req.version());
    }
  }

  @Override
  public void onResponse(final T response) {
    if (response.remoteAddress() != null) {
      DECORATE.onPeerConnection(span, response.remoteAddress().address());
    }

    if (response instanceof GetResponse) {
      final GetResponse resp = (GetResponse) response;
      span.setTag("opensearch.id", resp.getId());
      span.setTag("opensearch.version", resp.getVersion());
    }

    if (response instanceof BroadcastResponse) {
      final BroadcastResponse resp = (BroadcastResponse) response;
      span.setTag("opensearch.shard.broadcast.total", resp.getTotalShards());
      span.setTag("opensearch.shard.broadcast.successful", resp.getSuccessfulShards());
      span.setTag("opensearch.shard.broadcast.failed", resp.getFailedShards());
    }

    if (response instanceof ReplicationResponse) {
      final ReplicationResponse resp = (ReplicationResponse) response;
      span.setTag("opensearch.shard.replication.total", resp.getShardInfo().getTotal());
      span.setTag("opensearch.shard.replication.successful", resp.getShardInfo().getSuccessful());
      span.setTag("opensearch.shard.replication.failed", resp.getShardInfo().getFailed());
    }

    if (response instanceof IndexResponse) {
      span.setTag("opensearch.response.status", ((IndexResponse) response).status().getStatus());
    }

    if (response instanceof BulkShardResponse) {
      final BulkShardResponse resp = (BulkShardResponse) response;
      span.setTag("opensearch.shard.bulk.id", resp.getShardId().getId());
      span.setTag("opensearch.shard.bulk.index", resp.getShardId().getIndexName());
    }

    if (response instanceof BaseNodesResponse) {
      final BaseNodesResponse resp = (BaseNodesResponse) response;
      if (resp.hasFailures()) {
        span.setTag("opensearch.node.failures", resp.failures().size());
      }
      span.setTag("opensearch.node.cluster.name", resp.getClusterName().value());
    }

    try {
      listener.onResponse(response);
    } finally {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onFailure(final Exception e) {
    DECORATE.onError(span, e);

    try {
      listener.onFailure(e);
    } finally {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
