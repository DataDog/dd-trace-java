package com.datadog.appsec.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.*;

public class AppSecConfigFactory {

  private static final Yaml yaml;
  private static final JsonAdapter<AppSecConfig> jsonAdapter;

  static {
    yaml = new Yaml(new AppSecConfigConstructor());

    jsonAdapter =
        new Moshi.Builder().add(new LegacyConfigJsonAdapter()).build().adapter(AppSecConfig.class);
  }

  private AppSecConfigFactory() {}

  public static AppSecConfig fromYamlFile(File file) throws FileNotFoundException {
    InputStream inputStream = new FileInputStream(file);
    return yaml.loadAs(inputStream, AppSecConfig.class);
  }

  public static String toLegacyFormat(AppSecConfig appSecConfig) {
    return jsonAdapter.toJson(appSecConfig);
  }

  // We're using dedicated constructor class for AppSecConfig because it's
  // loaded by appsec Classloader, but Moshi loaded by shared Classloader.
  // Also this constructor disables case sensitivity for Enums that allows
  // to use uppercase Enums with lowercase values.
  private static class AppSecConfigConstructor extends Constructor {
    private final ClassLoader loader;

    private static final LoaderOptions loaderOptions;

    static {
      loaderOptions = new LoaderOptions();
      loaderOptions.setEnumCaseSensitive(false);
    }

    public AppSecConfigConstructor() {
      this(Object.class, AppSecConfig.class.getClassLoader());
    }

    public AppSecConfigConstructor(Class<?> theRoot, ClassLoader theLoader) {
      super(theRoot, loaderOptions);
      if (theLoader == null) {
        throw new NullPointerException("Loader must be provided.");
      }
      this.loader = theLoader;

      // Since rule conditions section can have different params depends on operation
      // (match_regex, phrase_match, etc.) we use custom constructor to build distinct
      // instances of params for different operations
      this.yamlClassConstructors.put(NodeId.mapping, new ConstructMapping() {
        @Override
        protected Object constructJavaBean2ndStep(MappingNode node, Object object) {

          // For conditions use custom parser
          if (node.getType().equals(Condition.class)) {
            NodeTuple operationNode = node.getValue().get(0);
            String key = constructScalar((ScalarNode) operationNode.getKeyNode());
            if ("operation".equalsIgnoreCase(key)) {
              Node valueNode = operationNode.getValueNode();
              valueNode.setType(Operation.class);
              String operation = constructScalar((ScalarNode) valueNode);

              // If operation is match_regex then use MatchRegexParams for params
              if ("match_regex".equalsIgnoreCase(operation)) {
                NodeTuple paramsNode = node.getValue().get(1);
                key = constructScalar((ScalarNode) paramsNode.getKeyNode());
                if ("params".equalsIgnoreCase(key)) {
                  valueNode = paramsNode.getValueNode();
                  valueNode.setType(MatchRegexParams.class);
                }
              }
            }
          }

          return super.constructJavaBean2ndStep(node, object);
        }
      });
    }

    @Override
    protected Class<?> getClassForName(String name) throws ClassNotFoundException {
      return Class.forName(name, true, loader);
    }
  }
}
