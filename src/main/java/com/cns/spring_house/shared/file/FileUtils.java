package com.cns.spring_house.shared.file;

import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import com.cns.spring_house.shared.exception.FileContentTypeException;
import com.cns.spring_house.shared.exception.FileProcessingException;

public class FileUtils {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private FileUtils() {

    }

    public static void validateImageType(MultipartFile file) {
        String contentType = file.getContentType();
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new FileContentTypeException(
                    "Solo se permiten archivos de tipo imagen (JPEG, PNG)");
        }
    }

    public static String extractExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.contains(".")) {
            throw new FileProcessingException("El archivo no tiene extensión válida");
        }
        return name.substring(name.lastIndexOf(".")).toLowerCase();
    }

}
