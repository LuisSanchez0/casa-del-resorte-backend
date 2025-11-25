package com.cns.spring_house.repository.redis;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RedisRepository {

    private final RedisTemplate<String, Object> template;

    public void save(String key, Object value, long timeToLive, TimeUnit unit) {
        template.opsForValue()
                .set(key, value, timeToLive, unit);
    }

    public Object findValueAndDelete(String key) {
        return template.opsForValue().getAndDelete(key);
    }

    public void delete(String key) {
        template.delete(key);
    }

}
