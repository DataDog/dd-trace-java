package datadog.smoketest


import java.util.concurrent.atomic.AtomicInteger

class SpringBootGrpcSpannerNoAsyncTest extends SpringBootWithGRPCTest {

  @Override
  boolean isAcceptable(Map<String, AtomicInteger> traceCounts) {
    // because of the way spanner's session creation works, without authentication,
    // we can't guarantee a deterministic flush with grpc.client spans attached to
    // their parents, but orphaned root grpc client spans (those without a parent id
    // to link to) are unacceptable because they can't be stitched together by the
    // backend. The writer will only report traces as [grpc.client] if the parent id
    // is zero. 
    if (!traceCounts.containsKey("[grpc.client]")) {
      for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
        if (entry.getKey().startsWith("[servlet.request[spring.handler[grpc.client]")
          || entry.getKey().startsWith("[servlet.request[spring.handler[http.request]")
          && entry.getValue().get() > 0) {
          return true
        }
      }
    }
    return false
  }

  @Override
  String route() {
    return "spanner_no_async"
  }
}
