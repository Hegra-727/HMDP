package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //开始时间戳
    private static final long BEGIN_TIMESTAMP=1672531200L;
    //序列号的位数（用于位运算补位的）
    private static final int COUNT_BITS=32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //3.拼接并返回
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);
        return timestamp<<COUNT_BITS|count;
    }

    public static void main(String[] args) {
        LocalDateTime time=LocalDateTime.of(2023,1,1,0,0,0);
        System.out.println(time.toEpochSecond(ZoneOffset.UTC));
        //1672531200 -2023-01-01 00:00:00
        //1695826361 -2023-09-27 14:54:54
    }
}
