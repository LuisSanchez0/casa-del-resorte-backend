package com.cns.spring_house.service.useraccount;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.useraccount.CreateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.SearchUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UpdateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountReponseDTO;

public interface UserAccountService {

    List<UserAccountReponseDTO> getAll(SearchUserAccountRequestDTO requestDTO);

    UserAccountReponseDTO get();

    UserAccountReponseDTO get(UUID id);

    UserAccountReponseDTO create(CreateUserAccountRequestDTO requestDTO);

    UserAccountReponseDTO update(UUID id, UpdateUserAccountRequestDTO requestDTO);

}
