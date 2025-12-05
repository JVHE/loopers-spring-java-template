package com.loopers.infrastructure.pg;

import feign.Request;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class PgClientConfig {

    @Bean
    public Request.Options feignRequestOptions() {
        return new Request.Options(Duration.ofSeconds(5), Duration.ofSeconds(5), true);
    }
}
