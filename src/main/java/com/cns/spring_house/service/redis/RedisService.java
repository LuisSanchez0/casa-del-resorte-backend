package com.cns.spring_house.service.redis;

public interface RedisService {

    void store(String key, Object value, long timeToLive);

    Object getValueAndDelete(String key);

    void delete(String key);

}
