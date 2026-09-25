package com.cns.spring_house.s3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.regions.Region;

@ConfigurationProperties(prefix = "aws.s3")
@Component
@Getter
@Setter
public class S3Properties {

    private Region region;
    private Bucket bucket = new Bucket();

    @Getter
    @Setter
    public static class Bucket {

        private String name;

    }

}