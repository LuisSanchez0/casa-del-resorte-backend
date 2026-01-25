package com.cns.spring_house.jwt.service;

import com.cns.spring_house.useraccount.entity.UserAccount;

public interface JwtService {

    String generateToken(UserAccount userAccount);

}
