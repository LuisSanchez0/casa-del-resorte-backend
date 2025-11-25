package com.cns.spring_house.dto.useraccount;

import java.util.UUID;

public record SearchUserAccountRequestDTO(

        UUID employee,
        UUID role,
        String username,
        Boolean active

) {
}
