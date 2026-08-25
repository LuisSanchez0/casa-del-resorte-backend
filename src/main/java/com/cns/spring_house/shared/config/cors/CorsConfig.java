package com.cns.spring_house.shared.config.cors;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties properties;

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
                        HttpMethod.DELETE.name())
                // Sin esto el navegador NO deja leer `Content-Disposition` desde
                // otro origen: la respuesta la trae, pero `headers.get(...)`
                // devuelve null y el cliente pierde el nombre con el que hay que
                // archivar el PDF del cierre de caja, que lo decide el servidor
                // porque es quien conoce la fecha operativa o los extremos del
                // rango. Es la unica cabecera de respuesta que el cliente
                // necesita leer, asi que se publica esa y no una lista abierta.
                .exposedHeaders(HttpHeaders.CONTENT_DISPOSITION);
    }

}
