package com.cns.spring_house.shared.config.openapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SPRING HOUSE REST API")
                        .version("1.0")
                        .description("REST API designed for the \"Spring House\" system."));
    }

}
