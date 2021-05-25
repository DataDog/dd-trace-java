package io.sqreen.testapp.sampleapp

import com.mongodb.MongoClient
import de.flapdoodle.embed.mongo.MongodExecutable
import io.sqreen.testapp.mongo.MongoConfiguration
import org.springframework.aop.framework.ProxyFactoryBean
import org.springframework.aop.target.LazyInitTargetSource
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.FactoryBean
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.orm.jpa.vendor.HibernateJpaSessionFactoryBean
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter

import javax.persistence.EntityManagerFactory

@SpringBootApplication
class Application extends WebMvcConfigurerAdapter {

  static void main(String[] args) {
    SpringApplication.run(this, args)
  }

  @Bean
  @Primary
  FactoryBean<AppSecInfo> appSecInfo() {
    new ProxyFactoryBean(proxyTargetClass: true,
    targetSource: lazyAppSecInfoTarget())
  }
  @Bean
  LazyInitTargetSource lazyAppSecInfoTarget() {
    new LazyInitTargetSource(targetBeanName: 'actualAppSecInfo')
  }
  @Bean
  @Lazy
  AppSecInfo actualAppSecInfo() {
    new AppSecInfo()
  }

  @Bean
  HibernateJpaSessionFactoryBean sessionFactory(EntityManagerFactory emf) {
    new HibernateJpaSessionFactoryBean(entityManagerFactory: emf)
  }

  @Bean
  @Lazy
  BeanFactory mongoApplicationContext(ApplicationContext applicationContext) {
    def context = new AnnotationConfigApplicationContext(parent: applicationContext)
    context.register MongoConfiguration
    context.refresh()
    context
  }

  @Bean
  @Lazy
  MongoClient mongo() {
    mongoApplicationContext(null).getBean('mongo')
  }

  @Bean
  @Lazy
  MongodExecutable embeddedMongoServer() {
    mongoApplicationContext(null).getBean('embeddedMongoServer')
  }

  @Override
  void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.add(new NameAndEmailHttpMessageConverter())
  }
}
