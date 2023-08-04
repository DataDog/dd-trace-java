package datadog.trace.bootstrap.config.provider;

import static datadog.trace.util.Strings.propertyNameToSystemPropertyName;

import datadog.trace.api.ConfigOrigin;

public final class SystemPropertiesConfigSource extends ConfigProvider.Source {

  @Override
  protected String get(String key) {
    return System.getProperty(propertyNameToSystemPropertyName(key));
  }

  @Override
  public ConfigOrigin origin() {
    // TODO there is no value for props in the spec
    // https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/conf_key_value.md#conf_key_value\
    // maybe another value is needed similar to app.config that only applies to .NET
    return ConfigOrigin.ENV;
  }
}
