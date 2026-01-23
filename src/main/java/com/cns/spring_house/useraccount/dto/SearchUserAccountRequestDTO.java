package com.cns.spring_house.useraccount.dto;

import java.util.UUID;

public record SearchUserAccountRequestDTO(

        UUID employee,
        UUID role,
        String username,
        Boolean active

) {
}
