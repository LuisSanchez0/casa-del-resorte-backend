package com.cns.spring_house.s3.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final S3Properties s3Properties;

    @Bean
    public S3Client customS3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                s3Properties.getAccessKeyId(),
                s3Properties.getSecretAccessKey());
        return S3Client.builder()
                .region(s3Properties.getRegion())
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();

    }

}
