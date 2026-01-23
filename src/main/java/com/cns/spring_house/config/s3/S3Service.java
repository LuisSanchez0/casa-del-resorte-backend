package com.cns.spring_house.config.s3;

import org.springframework.web.multipart.MultipartFile;

public interface S3Service {
    
    String uploadImage(MultipartFile file);

    void deleteObject(String key); 

}
