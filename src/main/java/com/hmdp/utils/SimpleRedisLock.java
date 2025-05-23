package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;
//线程安全的锁
public class SimpleRedisLock implements  ILock{

    private  String name;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) +"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //拿到当前线程的ID
       String threadId = ID_PREFIX + Thread.currentThread().getId();
        //1.获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId+"", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }
/*    @Override 这个方法被注释掉是因为它没有具备原子性，会导致在释放锁时如果发生阻塞，会导致其他线程的锁被释放，从而导致业务逻辑错误。
    public void unlock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //释放锁
        if (threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
