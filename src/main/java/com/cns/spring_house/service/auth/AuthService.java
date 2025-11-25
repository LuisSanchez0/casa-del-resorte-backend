package com.cns.spring_house.service.auth;

import java.util.Map;

import com.cns.spring_house.dto.auth.LoginRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountResponseDTO;

public interface AuthService {

    Map<String, String> login(LoginRequestDTO requestDTO);

    Map<String, String> refresh(String refreshToken);

    void logout(String refreshToken);

    UserAccountResponseDTO getUserAccount();

}
