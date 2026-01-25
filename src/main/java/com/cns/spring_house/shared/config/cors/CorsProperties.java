package com.cns.spring_house.shared.config.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties(prefix = "app.cors")
@Component
@Getter
@Setter
public class CorsProperties {

    private String origin;

}
