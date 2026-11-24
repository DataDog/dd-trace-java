package com.datadog.appsec.event.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KnownAddressesForkedTest {

  @ParameterizedTest(name = "forName works for address {0}")
  @ValueSource(
      strings = {
        "server.request.body",
        "server.request.body.raw",
        "_server.request.scheme",
        "server.request.uri.raw",
        "server.request.client_ip",
        "_server.request.client_port",
        "http.client_ip",
        "server.request.method",
        "server.request.path_params",
        "server.request.cookies",
        "server.request.transport",
        "server.response.status",
        "server.response.body.raw",
        "server.response.headers.no_cookies",
        "server.request.body.files_field_names",
        "server.request.body.filenames",
        "server.request.body.combined_file_size",
        "server.request.body.files_content",
        "server.request.query",
        "server.request.headers.no_cookies",
        "grpc.server.method",
        "grpc.server.request.message",
        "grpc.server.request.metadata",
        "graphql.server.all_resolvers",
        "graphql.server.resolver",
        "server.db.system",
        "server.db.statement",
        "usr.id",
        "usr.login",
        "usr.session_id",
        "server.business_logic.users.login.failure",
        "server.business_logic.users.login.success",
        "server.business_logic.users.signup",
        "server.io.net.url",
        "server.io.net.request.headers",
        "server.io.net.request.method",
        "server.io.net.request.body",
        "server.io.net.response.status",
        "server.io.net.response.headers",
        "server.io.net.response.body",
        "server.io.fs.file",
        "server.io.fs.file_write",
        "server.sys.exec.cmd",
        "server.sys.shell.cmd",
        "waf.context.processor"
      })
  void forNameWorksForAddress(String name) {
    assertEquals(name, KnownAddresses.forName(name).getKey());
  }

  @Test
  void numberOfKnownAddressesIsExpectedNumber() {
    assertEquals(47, Address.instanceCount());
    assertEquals(Address.instanceCount() - 1, KnownAddresses.WAF_CONTEXT_PROCESSOR.getSerial());
  }
}
