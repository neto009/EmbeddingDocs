package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient lmStudioWebClient() {
        HttpClient httpClient = HttpClient.create()
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .responseTimeout(java.time.Duration.ofSeconds(180))
            .doOnConnected(conn -> {
                conn.addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(180));
                conn.addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(180));
            });

        return WebClient.builder()
            .baseUrl("http://127.0.0.1:1234/v1")
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer lm-studio")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
    }
}
