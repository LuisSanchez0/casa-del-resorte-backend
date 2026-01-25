package com.cns.spring_house.s3.service;

import org.springframework.web.multipart.MultipartFile;

public interface S3Service {

    String uploadImage(MultipartFile file);

    void deleteObject(String key);

}
