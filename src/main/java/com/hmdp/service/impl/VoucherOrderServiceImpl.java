package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Component
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        //2.判断结果是否为0
        int r=result.intValue();
        if(r!=0){
            //2.1 如果不是0，说明没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 如果是0，说明有购买资格，把下单信息保存到阻塞队列中
        long orderId = redisIdWorker.nexId("order");
        //todo 保存到阻塞队列
        //3.返回订单id
        return Result.ok(orderId);

    }
    //java代码操纵数据库版本
   /* @Override
    public Result seckillVoucher(Long voucherId) {
        //1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
           return Result.fail("秒杀已经结束");
        }

        //4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
*//*        //创建锁对象(分布式锁)
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);*//*
        //利用 redisson 创建分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败，返回失败或者重试
            return Result.fail("不允许重复下单");
        }try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);//下面的方法是用ctrl+alt+m快捷键抽出来的
        }finally {
            lock.unlock();
        }

    }*/

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();

            //5.1查询订单
            Integer count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            //5.2判断是否存在
            if (count > 0) {
                return Result.fail("用户已经购买过一次了");
            }


            //6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id", voucherId).gt("stock", 0)//这里第二个.eq就是增加了一个判断条件，相当于where stock=,从而达到一个乐观锁的效果,避免并发问题(CAS)
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }
            //7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //7.1订单id
            long orderID = redisIdWorker.nexId("order");
            voucherOrder.setId(orderID);
            //7.2用户id
            Long userID = UserHolder.getUser().getId();
            voucherOrder.setUserId(userID);

            //7.3代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //8.返回订单id
            return Result.ok(orderID);

    }
}
