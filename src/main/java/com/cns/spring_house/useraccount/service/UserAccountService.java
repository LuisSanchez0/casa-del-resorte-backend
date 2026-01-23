package com.cns.spring_house.useraccount.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.useraccount.dto.CreateUserAccountRequestDTO;
import com.cns.spring_house.useraccount.dto.SearchUserAccountRequestDTO;
import com.cns.spring_house.useraccount.dto.UpdateUserAccountRequestDTO;
import com.cns.spring_house.useraccount.dto.UserAccountResponseDTO;

public interface UserAccountService {

    List<UserAccountResponseDTO> getAll(SearchUserAccountRequestDTO requestDTO);

    UserAccountResponseDTO get(UUID id);

    UserAccountResponseDTO create(CreateUserAccountRequestDTO requestDTO);

    UserAccountResponseDTO update(UUID id, UpdateUserAccountRequestDTO requestDTO);

}
