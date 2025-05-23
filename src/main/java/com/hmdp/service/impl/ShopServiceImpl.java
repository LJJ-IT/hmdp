package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    @Override
    public Result queryById(Long id) {
        //缓存穿透
/*        Shop shop = cacheClient
                  .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id,Shop.class,this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);*/

        //互斥锁解决缓存击穿
        Shop shop = cacheClient
                .queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //逻辑过期解决缓存击穿,用这个得先手动设置expireTime过期时间(因为他将shop作为data存到了RedisData的成员了,另一个成员就是expireTime)，即先手动缓存数据，一般用在热点数据的缓存中
/*        Shop shop = cacheClient
                .queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);*/
        if(shop == null){
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);

    }

    //下面的方法都封装在 CacheClient 类中，并且里面运用了很多几个关键点：泛型，function有参有返回值（将方法作为参数）,lambda表达式
/*    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    *//**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     *//*
    public Shop queryWithLogicExpire(Long id){

        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //3.不存在，直接返回空
            return null;

        }
        //4.命中，需要先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 没有过期，直接返回
            return shop;
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
                    //重建缓存
                    this.saveShop2Redis(id,CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }

        //6.4 失败，返回过期的商铺信息
        return shop;

    }

    *//**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     *//*
    public Shop queryWithMutex(Long id){

        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断命中的数据是否为空（这里的null不是空字符串，所以是!=）
        if(shopJson!=null){
            //返回错误信息
            return null;
        }
        //4.实现缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;//拼接出来锁的key
        Shop shop = null;

        try {

            //4.1获取互斥锁
            boolean isLock = trylock(lockKey);
            //4.2判断是否获取锁成功
            if (!isLock){
            //4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4成功，获取锁成功，根据id查询数据库
            shop = getById(id);
            //模拟重建的延迟
            Thread.sleep(200);
            //5.不存在返回错误
            if(shop == null){
                //将空值存储到redis中
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在，写入缓存
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }


        //8.返回数据
        return shop;

    }

    *//**
     * 逻辑过期解决缓存穿透
     * @param id
     * @return
     *//*
    public Shop queryWithPassThrough(Long id){

        // 设置缓存时添加一个随机时间偏移量（例如：0到5分钟之间的随机分钟数）,防止缓存雪崩
        int randomTtl = (int) (Math.random() * 6); // 生成0到5之间的随机整数

        //1.从redis中查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断命中的数据是否为空（空字符串）
        if(shopJson!=null){
            return null;
        }
        //4.不存在，查询数据库
        Shop shop = getById(id);
        //5.不存在返回错误
        if(shop == null){
            //将空值存储到redis中
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL+randomTtl, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，写入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL+randomTtl, TimeUnit.MINUTES);
        //7.返回数据
        return shop;

    }
    //创建锁
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//返回true表示获取锁成功，false表示获取锁失败(拆箱：对象转化成基本数据类型)
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    //逻辑时间缓存重建
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);        //模拟重建的延迟
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }*/

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 查询类型查询店铺，并且加上距离功能
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));//注意这个分页参数不需要改，前端滚动不更新数据是因为前端的页面太长了，拉短一点前端才能判断出滚动的边界条件
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;

        // 使用 GEORADIUS 替换 GEOSEARCH
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()  // 包含距离
                .sortAscending()    // 按距离升序排序
                .limit(end);        // 限制结果数量

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key, new Circle(new Point(x, y), new Distance(5000)), args);


        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

}
