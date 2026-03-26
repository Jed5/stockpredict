package com.stock.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockPredictOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("A股智能选股系统 API")
                .version("1.0.0")
                .description("基于技术分析与机器学习的A股智能选股REST API")
                .contact(new Contact().name("StockPredict Team").url("https://github.com/Jed5/stockpredict")));
    }
}
