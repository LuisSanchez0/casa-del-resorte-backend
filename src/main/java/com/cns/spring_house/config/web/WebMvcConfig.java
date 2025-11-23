package com.cns.spring_house.config.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cns.spring_house.config.ClientProperties;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final ClientProperties properties;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
				.allowCredentials(true)
				.allowedOrigins(properties.getOrigin())
				.allowedHeaders(
						HttpHeaders.ORIGIN,
						HttpHeaders.CONTENT_TYPE,
						HttpHeaders.ACCEPT,
						HttpHeaders.AUTHORIZATION)
				.allowedMethods(
						HttpMethod.GET.name(),
						HttpMethod.POST.name(),
						HttpMethod.PATCH.name(),
						HttpMethod.DELETE.name());
	}

}
