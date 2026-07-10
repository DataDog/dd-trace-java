# Add a New Configuration

This doc will walk through how to properly add and document a new [configuration](https://docs.datadoghq.com/tracing/trace_collection/library_config/java/) in the Java Library.

## Where Configurations Live

All configurations in the Java Library are defined in the package `dd-trace-api/src/main/java/datadog/trace/api/config`. 
Configurations are separated into different files based on the product they are related to. e.g. `CiVisiblity` related configurations live in `CiVisibilityConfig.java`, `Tracer` related in `TracerConfig.java`, etc. 
Default values for configurations live in `ConfigDefaults.java`.  

Configuration values are read and stored in `Config.java`, which utilizes helper methods in `ConfigProvider.java` to fetch the final value for a configuration after searching through all Configuration Sources and determining the final value based on Source priority. Below is the list of Sources that are queried for assigning Configuration values in order from highest to lowest priority:
1. System Properties: JVM System Properties
2. Stable Config - Fleet Automation: Now known as Declarative Configuration, this source reads a list of configurations through a `.yaml` file on Fleet Automation to take effect on all instances on a host.
3. CI Environment Variables: Source for Configurations related to the `CiVisibility` product. 
4. Environment Variables: JVM Environment Variables
5. Properties File: By defining a filepath in `DD_TRACE_CONFIG`/`trace.config`, users can define Configuration key/value pairs in the file
6. OpenTelemetry Environment Variables: A list of OpenTelemetry Configurations that are supported in the Java Library. See [OtelEnvironmentConfigSource.java](../utils/config-utils/src/main/java/datadog/trace/bootstrap/config/provider/OtelEnvironmentConfigSource.java) for a list of supported OpenTelemetry Configurations.
7. Stable Config - Local File: Now known as Declarative Configuration, this source reads a list of configurations through a local `.yaml` file on set by the user to take effect on all instances on a host.
8. Captured Environment Variables: Auto-detects values for certain Configurations. Essentially setting "default" values for specific Configurations. 

Additionally, `Config.java` also includes getters that can be used in other classes to get the value of a configuration. These getters should be the only method used to query the value of a configuration. Do **NOT** use `ConfigProvider.java` to re-query the values of a configuration.

## Adding a Configuration

In order to properly add a new configuration in the library, follow the below steps.
1. Determine whether this configuration exists in another tracing library in the [Feature Parity Dashboard](https://feature-parity.us1.prod.dog/#/configurations?viewType=configurations). Developers can search by Environment Variable name or description of the configuration.
   1. If the configuration exists in a separate tracing library, reuse the name of the existing configuration. If the configuration already exists in the Java Library, utilize that instead.
2. Add the definition of the configuration to the appropriate "Config" class based on its related product.
   1. When choosing a configuration definition, consider separating words by `.` and compound words by `-`. Note that `dd.` or `DD_` should not belong in the configuration definition as the base definitions are normalized to include those prefixes when querying the varying Configuration Sources. (e.g. `public static final String DOGSTATSD_START_DELAY = "dogstatsd.start-delay"`)
3. If a non-null default value for the configuration exists, define the value in a static field in `ConfigDefaults.java`.
4. Create a local field in `Config.java` to represent the configuration. In the constructor of `Config.java`, call the appropriate helper from `ConfigProvider.java` to query and assign the value of the configuration based off what datatype the Configuration expects to store. (e.g. `ConfigProvider::getString`, `ConfigProvider::getBoolean`, etc.)
   1. This field should be final and not changed during runtime. If the value of a configuration needs to be changed, it can be done through a Snapshot with Dynamic Configuration. See [DynamicConfig.java](../internal-api/src/main/java/datadog/trace/api/DynamicConfig.java).
5. Create a getter for the field in `Config.java` to allow other classes to access the value of the configuration.
6. Add the configuration to the `toString()` method of `Config.java` for logging purposes.
7. Add the Environment Variable name of the configuration to the `supportedConfigurations` key of `metadata/supported-configurations.json`.
   1. The key is the Environment Variable name, and the value is an array of objects with the following fields:
      1. Version. 
         1. If introducing a new configuration, provide a version of `A`.
         2. If the configuration already exists in the Feature Parity Dashboard and has the same implementation details as an existing Configuration Version, add the version listed on the Feature Parity Dashboard. Else, introduce a new Configuration Version, fill in the proper documentation, and use the version provided.
      2. Type. This is a _mandatory_ field and has the options of boolean, int, decimal, string, map, array. If the configuration is eventually converted to an Enum or other class, use type String.
      3. Default. This is a _mandatory_ field and accepts null as a valid value.
      4. Aliases. This is a _mandatory_ field. If there are no aliases for the configuration, use an empty array as the value.
      5. PropertyKeys. This is an _optional_ field that should only be used if there are additional telemetry keys being sent from the tracer (that are not the environment variable itself).

See below for an example of the `supported-configurations.json` file.
```
{
  "supportedConfigurations": {
    "DD_SERVICE": [
      {
        "version": "D", // Mandatory, generated by Feature Parity Dashboard
        "type": "string", // Mandatory, choose from boolean, int, decimal, string, map, array 
        "default": null, // Mandatory, allows null values
        "aliases": ["DD_SERVICE_NAME"] // Mandatory, allows empty array if no aliases exist
      }
    ],
    "DD_ENV_WITH_TELEMETRY_KEYS": [
      {
        "version": "A", // Mandatory, generated by Feature Parity Dashboard
        "type": "boolean", // Mandatory, choose from boolean, int, decimal, string, map, array
        "default": "true", // Mandatory, allows null values
        "aliases": [], // Mandatory, allows empty array if no aliases exist
        "propertyKeys": ["test.telemetry"] // Optional, If none exist, omit this key 
      }
    ],
  }
  "deprecations": {
    "DD_LEGACY_SERVICE_NAME": "use DD_SERVICE instead"
  }
}
```
8. If the configuration is unique to all tracing libraries, add it to the [Feature Parity Dashboard](https://feature-parity.us1.prod.dog/#/configurations?viewType=configurations). This ensures that we have good documentation of all configurations supported in the library.

For details on adding environment variables to `metadata/supported-configurations.json` or the Feature Parity Dashboard, refer to this [document](https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/5372248222/APM+-+Centralized+Configuration+Config+inversion#dd-trace-java).

## Verifying the Configuration

To verify a configuration has been correctly added, developers can modify existing test cases in `ConfigTest.groovy` to set the value of the configuration with various sources and confirm the internal value is correctly set in `Config.java`.
Optionally, new test cases can be added for testing specific to the behavior of a configuration.
