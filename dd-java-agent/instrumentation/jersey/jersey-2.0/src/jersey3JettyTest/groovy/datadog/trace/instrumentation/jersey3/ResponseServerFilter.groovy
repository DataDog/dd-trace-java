package datadog.trace.instrumentation.jersey3


import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

import static datadog.trace.agent.test.base.HttpServerTest.IG_RESPONSE_HEADER
import static datadog.trace.agent.test.base.HttpServerTest.IG_RESPONSE_HEADER_VALUE

@Provider
class ResponseServerFilter implements ContainerResponseFilter {
  @Override
  void filter(ContainerRequestContext requestContext,
    ContainerResponseContext responseContext) throws IOException {
    responseContext.getHeaders().add(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
  }
}
