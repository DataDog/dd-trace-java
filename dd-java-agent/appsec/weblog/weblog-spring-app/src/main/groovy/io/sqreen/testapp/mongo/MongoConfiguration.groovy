package io.sqreen.testapp.mongo

import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import de.flapdoodle.embed.mongo.MongodExecutable
import de.flapdoodle.embed.mongo.MongodStarter
import de.flapdoodle.embed.mongo.config.IMongodConfig
import de.flapdoodle.embed.process.config.IRuntimeConfig
import org.bson.Document
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.mongo.MongoProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySource
/* in a separate package to avoid it being auto-discovered */
@Configuration
@EnableConfigurationProperties(MongoProperties)
class MongoConfiguration {

  @Bean(destroyMethod = 'close')
  MongoClient mongo(MongoProperties properties,
    ObjectProvider<MongoClientOptions> options,
    Environment environment,
    MongodExecutable exec /* just for ordering */) {
    properties.createMongoClient(options.getIfAvailable(), environment)
  }


  @Bean(initMethod = 'start', destroyMethod = 'stop')
  MongodExecutable embeddedMongoServer(ApplicationContext context,
    MongoProperties properties,
    IMongodConfig mongodConfig,
    IRuntimeConfig runtimeConfig) throws IOException {
    Integer configuredPort = properties.port
    if (configuredPort == null || configuredPort == 0) {
      setPortProperty(context, mongodConfig.net().port)
    }

    MongodStarter mongodStarter = getMongodStarter(runtimeConfig)
    (MongodExecutable)mongodStarter.prepare(mongodConfig)
  }

  @Bean
  FactoryBean<Object> createSampleDocuments() {
    def database = mongo(null, null, null, null).getDatabase('db')
    def collection = database.getCollection('people')
    Document doc = new Document('name', 'Joseph')
      .append('profession', 'carpenter')
    collection.insertOne(doc)
    new EmptyFactoryBean()
  }

  private void setPortProperty(ApplicationContext currentContext, int port) {
    if (currentContext instanceof ConfigurableApplicationContext) {
      MutablePropertySources sources = ((ConfigurableApplicationContext)currentContext).environment.propertySources
      this.getMongoPorts(sources).put('local.mongo.port', port)
    }

    if (currentContext.parent) {
      setPortProperty(currentContext.parent, port)
    }
  }

  private Map<String, Object> getMongoPorts(MutablePropertySources sources) {
    PropertySource<?> propertySource = sources.get('mongo.ports')
    if (!propertySource) {
      propertySource = new MapPropertySource('mongo.ports', [:])
      sources.addFirst((PropertySource)propertySource)
    }

    ((PropertySource)propertySource).source
  }

  private MongodStarter getMongodStarter(IRuntimeConfig runtimeConfig) {
    runtimeConfig == null ? MongodStarter.defaultInstance : MongodStarter.getInstance(runtimeConfig)
  }

  private static final class EmptyFactoryBean implements FactoryBean<Object> {
    boolean singleton = false
    Object object
    Class<?> objectType = Object
  }
}
