package com.cns.spring_house.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties(prefix = "app.client")
@Component
@Getter
@Setter
public class WebProperties {

    private String origin;

}
