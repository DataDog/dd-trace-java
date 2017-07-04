package com.datadoghq.trace.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentracing.contrib.okhttp3.TracingInterceptor;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class OkHTTPInstrumentationTest {

    @Test
    public void test() {
        OkHttpClient client = new OkHttpClient().newBuilder().build();

        assertThat(client.interceptors().size()).isEqualTo(1);
        assertThat(client.interceptors().get(0).getClass()).isEqualTo(TracingInterceptor.class);
    }

}
