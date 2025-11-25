package com.cns.spring_house.service.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.config.properties.SecurityProperties;
import com.cns.spring_house.dto.auth.LoginRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountResponseDTO;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.mapper.useraccount.UserAccountMapper;
import com.cns.spring_house.model.useraccount.UserAccount;
import com.cns.spring_house.repository.redis.RedisRepository;
import com.cns.spring_house.repository.useraccount.UserAccountRepository;
import com.cns.spring_house.service.jwt.JwtService;
import com.cns.spring_house.util.RedisKeys;
import com.cns.spring_house.util.Token;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

	private final JwtService jwtService;
	private final RedisRepository redisRepository;
	private final UserAccountRepository userAccountRepository;
	private final SecurityProperties securityProperties;
	private final PasswordEncoder passwordEncoder;
	private final ObjectMapper objectMapper;
	private final UserAccountMapper mapper;

	@Override
	public Map<String, String> login(LoginRequestDTO requestDTO) {
		UserAccount userAccount = userAccountRepository
				.findByUsername(requestDTO.username())
				.orElseThrow(() -> new BadCredentialsException(
						"Nombre de usuario o contraseña incorrectos"));
		if (!passwordEncoder.matches(requestDTO.password(), userAccount.getPassword())) {
			throw new BadCredentialsException(
					"Nombre de usuario o contraseña incorrectos");
		}
		return buildAuthTokens(userAccount);
	}

	@Override
	public Map<String, String> refresh(String refreshToken) {
		String key = RedisKeys.REFRESH_TOKEN + refreshToken;
		Object value = redisRepository.findValueAndDelete(key);
		if (value == null) {
			throw new BadCredentialsException("El token es invalido");
		}
		UUID id;
		try {
			id = objectMapper.convertValue(value, UUID.class);
		} catch (IllegalArgumentException e) {
			throw new BadCredentialsException("El token es invalido");
		}
		UserAccount userAccount = userAccountRepository
				.findById(id)
				.orElseThrow(() -> new BadCredentialsException("El token es invalido"));
		return buildAuthTokens(userAccount);
	}

	@Override
	public void logout(String refreshToken) {
		String key = RedisKeys.REFRESH_TOKEN + refreshToken;
		redisRepository.delete(key);
	}

	private Map<String, String> buildAuthTokens(UserAccount userAccount) {
		if (!userAccount.getIsActive()) {
			throw new DisabledException("El usuario esta desactivado");
		}
		String accessToken = jwtService.generateToken(
				userAccount.getId(),
				userAccount.getRole().getName());
		String refreshToken = Token.generateSecureToken(32);
		String key = RedisKeys.REFRESH_TOKEN + refreshToken;
		redisRepository.save(
				key,
				userAccount.getId(),
				securityProperties.getRefreshToken().getExpirationTime(),
				TimeUnit.MILLISECONDS);
		return Map.of(
				"accessToken", accessToken,
				"refreshToken", refreshToken);
	}

	@Override
	public UserAccountResponseDTO getUserAccount() {
		JwtAuthenticationToken authenticationToken = (JwtAuthenticationToken) SecurityContextHolder
				.getContext()
				.getAuthentication();
		UUID id = UUID.fromString(authenticationToken.getName());
		UserAccount userAccount = userAccountRepository
				.findById(id)
				.orElseThrow(() -> new EntityNotFoundException(
						"No existe la cuenta de usuario solicitada"));
		return mapper.toResponse(userAccount);
	}

}
