package com.cns.spring_house.service.useraccount;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.useraccount.CreateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.SearchUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UpdateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountResponseDTO;

public interface UserAccountService {

    List<UserAccountResponseDTO> getAll(SearchUserAccountRequestDTO requestDTO);

    UserAccountResponseDTO get(UUID id);

    UserAccountResponseDTO create(CreateUserAccountRequestDTO requestDTO);

    UserAccountResponseDTO update(UUID id, UpdateUserAccountRequestDTO requestDTO);

}
