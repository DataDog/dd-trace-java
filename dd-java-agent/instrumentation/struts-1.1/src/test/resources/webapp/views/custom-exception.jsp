  <%@ page contentType="application/json; charset=UTF-8" %>
      <% response.setStatus(510); %>
      <%= datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION.getBody() %>
