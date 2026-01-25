package com.cns.spring_house.s3.service;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cns.spring_house.s3.config.S3Properties;
import com.cns.spring_house.shared.exception.FileProcessingException;
import com.cns.spring_house.shared.file.FileUtils;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private final S3Properties s3Properties;
    private final S3Client s3Client;

    @Override
    public String uploadImage(MultipartFile file) {
        FileUtils.validateImageType(file);
        String fileExtension = FileUtils.extractExtension(file);
        String fileName = UUID.randomUUID() + fileExtension;
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(s3Properties.getBucket().getName())
                .key(fileName)
                .contentType(file.getContentType())
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
            return fileName;
        } catch (IOException e) {
            throw new FileProcessingException("Error al leer el archivo enviado");
        }
    }

    @Override
    public void deleteObject(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(s3Properties.getBucket().getName())
                .key(key)
                .build();
        s3Client.deleteObject(request);
    }

}
