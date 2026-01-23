package com.cns.spring_house.auth.service;

import java.util.Map;

import com.cns.spring_house.auth.dto.LoginRequestDTO;
import com.cns.spring_house.useraccount.dto.UserAccountResponseDTO;

public interface AuthService {

    Map<String, String> login(LoginRequestDTO requestDTO);

    Map<String, String> refresh(String refreshToken);

    void logout(String refreshToken);

    UserAccountResponseDTO getUserAccount();

}
