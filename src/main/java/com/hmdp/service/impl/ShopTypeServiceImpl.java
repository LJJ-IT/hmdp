package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有商铺类型
     * @return
     */
    @Override
    public List<ShopType> getList() {
        // 1. 查询是否存在于redis中
        String redisKey = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String cachedData = stringRedisTemplate.opsForValue().get(redisKey);

        List<ShopType> shopTypes = null;

        // 2. 判断是否存在
        if (StringUtils.hasText(cachedData)) {
            // 3. 存在，读取缓存（假设缓存的是JSON字符串，使用Jackson或Fastjson解析）
            shopTypes = JSONUtil.toList(cachedData, ShopType.class);
        } else {
            // 4. 不存在，查询数据库
            shopTypes = query().orderByAsc("sort").list();

            // 5. 数据库中也不存在
            if (shopTypes == null || shopTypes.isEmpty()) {
                return (List<ShopType>) Result.fail("商铺类型不存在");
            }

            // 6. 存在，写入缓存
            stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(shopTypes));
        }

        // 7. 返回数据
        return shopTypes;
    }

}
