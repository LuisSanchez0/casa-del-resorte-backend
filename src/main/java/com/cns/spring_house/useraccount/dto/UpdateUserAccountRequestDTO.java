package com.cns.spring_house.useraccount.dto;

import java.util.UUID;

public record UpdateUserAccountRequestDTO(

        UUID role,
        String username,
        String password,
        Boolean isActive

) {
}
