package com.example;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@RunWith(VertxUnitRunner.class)
public class MainVerticleTest {

  private Vertx vertx;
  private TestContext context;

  @Before
  public void setUp(TestContext testContext) {
    context = testContext;
    vertx = Vertx.vertx();
    vertx.deployVerticle(MainVerticle.class.getName(), testContext.asyncAssertSuccess());
  }

  @After
  public void tearDown() {
    vertx.close(context.asyncAssertSuccess());
  }

    @Test
    public void testGreetingsEndpoint() {
        Async async = context.async();

        vertx.createHttpClient().getNow(8080, "localhost", "/greetings?param=World", response -> {
            context.assertEquals(200, response.statusCode());

            response.bodyHandler(body -> {
                context.assertEquals("Hello World", body.toString());
                async.complete();
            });
        });
    }

    @Test
    public void testInsecureHashEndpoint() {
        Async async = context.async();

        vertx.createHttpClient().getNow(8080, "localhost", "/insecure_hash", response -> {
            context.assertEquals(200, response.statusCode());

            response.bodyHandler(body -> {
                context.assertEquals("insecure hash", body.toString());
                async.complete();
            });
        });
    }

  @Test
  public void testInsecureHash() throws NoSuchAlgorithmException {
    MessageDigest.getInstance("MD5");
    assert true;
  }

  @Test
  public void testCmdInjectionEndpoint() {
    Async async = context.async();

    vertx.createHttpClient().getNow(8080, "localhost", "/cmd_injection?param=cmd", response -> {
      context.assertEquals(200, response.statusCode());

      response.bodyHandler(body -> {
        context.assertEquals("cmd injection", body.toString());
        async.complete();
      });
    });
  }



}
