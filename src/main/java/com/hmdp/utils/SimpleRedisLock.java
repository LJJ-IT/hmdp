package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;


import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILock{

    private  String name;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    @Override
    public boolean tryLock(long timeoutSec) {
        //拿到当前线程的ID
        long threadId = Thread.currentThread().getId();
        //1.获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId+"", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
