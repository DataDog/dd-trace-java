package com.datadog.appsec.event.data

import spock.lang.Specification

class KnownAddressesSpecification extends Specification {
  void 'forName works for address #name'() {
    expect:
    KnownAddresses.forName(name).key == name

    where:
    name << [
      "server.request.body",
      "server.request.body.raw",
      "_server.request.scheme",
      "server.request.uri.raw",
      "server.request.client_ip",
      "_server.request.client_port",
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
      "server.request.query",
      "server.request.headers.no_cookies",
      "grpc.server.request.message"
    ]
  }

  void 'number of known addresses is expected number'() {
    expect:
    Address.instanceCount() == 19
    KnownAddresses.GRPC_SERVER_REQUEST_MESSAGE.serial == Address.instanceCount() - 1
  }
}
