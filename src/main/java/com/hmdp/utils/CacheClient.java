package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 设置普通缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 逻辑过期
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 逻辑过期解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {

        // 设置缓存时添加一个随机时间偏移量（例如：0到5分钟之间的随机分钟数）,防止缓存雪崩
        int randomTtl = (int) (Math.random() * 6); // 生成0到5之间的随机整数

        //1.从redis中查询缓存
        String key = keyPrefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);

        }
        //判断命中的数据是否为空（空字符串）
        if(shopJson!=null){
            return null;
        }
        //4.不存在，查询数据库
        R r = dbFallback.apply(id);
        //5.不存在返回错误
        if(r == null){
            //将空值存储到redis中
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL+randomTtl, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入缓存
        this.set(key,r,time,unit);
        //7.返回数据
        return r;

    }

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public <R,ID> R queryWithMutex(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){

        String key = keyPrefix + id;
        //1.从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        //判断命中的数据是否为空（这里的null不是空字符串，所以是!=）
        if(json!=null){
            //返回错误信息
            return null;
        }
        //4.实现缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;//拼接出来锁的key
        R r = null;

        try {

            //4.1获取互斥锁
            boolean isLock = trylock(lockKey);
            //4.2判断是否获取锁成功
            if (!isLock){
                //4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            //4.4成功，获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            //模拟重建的延迟
            Thread.sleep(200);
            //5.不存在返回错误
            if(r == null){
                //将空值存储到redis中
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,unit);
                //返回错误信息
                return null;
            }
            //6.存在，写入缓存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回数据
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){

        String key = keyPrefix + id;
        //1.从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(json)){
            //3.不存在，直接返回空
            return null;

        }
        //4.命中，需要先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 没有过期，直接返回
            return r;
        }
        //5.2 过期了，缓存重建
        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = trylock(lockKey);
        //6.2 判断是否获取成功
        if(isLock){
            //6.3 成功,开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }

        //6.4 失败，返回过期的商铺信息
        return r;

    }

    /**
     * 创建锁
     * @param key
     * @return
     */
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//返回true表示获取锁成功，false表示获取锁失败(拆箱：对象转化成基本数据类型)
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
