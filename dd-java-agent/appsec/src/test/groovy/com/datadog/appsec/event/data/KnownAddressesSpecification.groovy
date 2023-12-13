package com.datadog.appsec.event.data

import spock.lang.Specification

class KnownAddressesSpecification extends Specification {
  void 'forName works for address #name'() {
    expect:
    KnownAddresses.forName(name).key == name

    where:
    name << [
      'server.request.body',
      'server.request.body.raw',
      '_server.request.scheme',
      'server.request.uri.raw',
      'server.request.client_ip',
      '_server.request.client_port',
      'http.client_ip',
      'server.request.method',
      'server.request.path_params',
      'server.request.cookies',
      'server.request.transport',
      'server.response.status',
      'server.response.body.raw',
      'server.response.headers.no_cookies',
      'server.request.body.files_field_names',
      'server.request.body.filenames',
      'server.request.body.combined_file_size',
      'server.request.query',
      'server.request.headers.no_cookies',
      'grpc.server.request.message',
      'grpc.server.request.metadata',
      'graphql.server.all_resolvers',
      'graphql.server.resolver',
      'usr.id',
      'waf.context.processor',
    ]
  }

  void 'number of known addresses is expected number'() {
    expect:
    Address.instanceCount() == 26
    KnownAddresses.WAF_CONTEXT_PROCESSOR.serial == Address.instanceCount() - 1
  }
}
