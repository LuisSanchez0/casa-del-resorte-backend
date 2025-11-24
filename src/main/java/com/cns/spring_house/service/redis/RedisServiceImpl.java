package com.cns.spring_house.service.redis;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisServiceImpl implements RedisService {

    private final RedisTemplate<String, Object> template;

    @Override
    public void store(String key, Object value, long timeToLive) {
        template.opsForValue()
                .set(key, value, timeToLive, TimeUnit.MILLISECONDS);
    }

    @Override
    public Object getValueAndDelete(String key) {
        return template.opsForValue().getAndDelete(key);
    }

    @Override
    public void delete(String key) {
        template.delete(key);
    }

}
