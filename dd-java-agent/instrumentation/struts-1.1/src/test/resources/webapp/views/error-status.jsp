  <%@ page contentType="application/json; charset=UTF-8" %>
      <% response.setStatus(500); %>
      <%= datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR.getBody() %>
