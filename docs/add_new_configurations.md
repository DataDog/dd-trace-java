# Add a New Configuration

This doc will walk through how to properly add and document a new configuration in the Java Tracer.

## Where Configurations Live

All configurations in the Java Tracer are defined in `dd-trace-api/src/main/java/datadog/trace/api/config`. 
Configurations are separated into different files based on the product they are related to. e.g. `CiVisiblity` related configurations live in `CiVisibilityConfig`, `Tracer` related in `TracerConfig`, etc. 
Default values for configurations live in `ConfigDefaults.java`.  

Configuration values are read in `Config.java`, which utilizes helper methods in `ConfigProvider.java` to fetch the final value for a configuration after searching through all Configuration Sources and determining the final value based on Source priority.
`Config.java` also includes getters that can be used in other classes to get the value of a configuration.

## Adding a Configuration

In order to properly add a new configuration in the tracer, follow the below steps.
1. Determine whether this configuration exists in another tracer in the [Feature Parity Dashboard](https://feature-parity.us1.prod.dog/#/configurations?viewType=configurations). Developers can search by Environment Variable name or description of the configuration.
   1. If the configuration exists in a separate tracer, reuse the name of the existing configuration. If the configuration already exists in the Java Tracer, utilize that instead.
2. Add the definition of the configuration to the appropriate class based on its related product.
   1. Consider separating words by `.` and compound words by `-`. Note that `dd.` or `DD_` should not belong in the configuration definition as the base definitions are normalized to include those prefixes when querying the varying Configuration Sources.
3. If relevant, add the default value of the configuration in a static field in `ConfigDefaults.java`.
4. Create a local field in `Config.java` to represent the configuration. In the constructor of `Config.java`, call the relevant helper from `ConfigProvider.java` to query and assign the value of the configuration.
5. Create a getter for the field for other classes to access the value of the configuration.
6. Add the configuration to the `toString()` method or logging.
7. Add the Environment Variable name of the configuration to the `supportedConfigurations` key of `metadata/supported-configurations.json` in the format of `ENV_VAR: ["VERSION", ...]`. If the configuration already existed in another tracer, add the version listed on the Feature Parity Dashboard. If introducing a new configuration, provide a version of `A`.
   1. If there are aliases of the Environment Variable, add them to the `aliases` key of the file.
8. If the configuration is unique to all tracers, add it to the [Feature Parity Dashboard](https://feature-parity.us1.prod.dog/#/configurations?viewType=configurations). This ensures that we have good documentation of all configurations supported in the tracer.

For details on adding environment variables to `metadata/supported-configurations.json` or the Feature Parity Dashboard, refer to this [document](https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/5372248222/APM+-+Centralized+Configuration+Config+inversion#dd-trace-java).

## Verifying the Configuration

To verify a configuration has been correctly added, developers can modify existing test cases in `ConfigTest.groovy` to set the value of the configuration with various sources and confirm the internal value is correctly set in `Config.java`.
Optionally, new test cases can be added for testing specific to the behavior of a configuration.
