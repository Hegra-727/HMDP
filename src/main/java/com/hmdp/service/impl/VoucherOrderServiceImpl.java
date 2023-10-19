package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
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

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息
                    List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //2.判断订单信息是否为空
                    if (list==null||list.isEmpty()){
                        continue;
                    }
                    //解析数据
                    MapRecord<String,Object,Object> record=list.get(0);
                    Map<Object,Object> value = record.getValue();
                    VoucherOrder voucherOrder= BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.创建订单
//                    handleVoucherOrder(voucherOrder);
                    handleVoucherOrder(voucherOrder);
                    //4.确认信息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明没有异常消息，结束循环
                    break;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                handleVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pending-list订单异常",e);
            }try {
                Thread.sleep(20);
            }catch (InterruptedException interruptedException){
                interruptedException.printStackTrace();
            }
        }
    }

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.判断订单信息是否为空

                    //3.创建订单
                    handleVoucherOrder(voucherOrder);
                    //4.确认信息

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId=voucherOrder.getUserId();
        //创建锁对象(新增代码)
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁对象
        boolean isLock = lock.tryLock();
        //加锁失败
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //不再需要返回订单信息，现在是void类型
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        //2.判断结果为0
        if (r != 0) {
            //2.1 r不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        /*//2.2 r为0，有资格，把下单信息保存到阻塞队列
        //保存到阻塞队列中，异步执行
        //在订单中存入订单id、用户id、代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);*/
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);
    }

    /**
     * 秒杀下单
     *
     * @param voucherId
     */
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher= seckillVoucherService.getById(voucherId);
        // 1.判断秒杀开始？
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        // 2.判断秒杀结束？
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
        // 3.判断库存不足？
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }
        // 4.扣减库存、（该事务在并发环境下，容易出现超卖问题）
        *//*boolean success=seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id",voucherId)
                .update();*//*
        //假设一段时间内，库存不变，则说明无其它并发进程干扰（理想化）
        *//*boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id",voucherId)
                .eq("stock",voucher.getStock())
                .update();*//*
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if (!success){
            return Result.fail("库存不足");
        }
        //释放锁的时机（粒度和生命周期）、事务是否生效（代理对象）？
//        Long userId = UserHolder.getUser().getId();
        //创建锁对象(新增代码)
//        SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:"+userId);
        //获取锁对象
//        boolean isLock = lock.tryLock(1200);
        boolean isLock=lock.tryLock();
        //加锁失败
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
*/
    //ctrl+alt+m,自动提取代码块创建全新的方法
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:"+userId);
        //尝试获取锁
        boolean isLock = redisLock.tryLock();
        if(!isLock){
            log.error("不允许重复下单！");
            return;
        }
        try {
            //锁住一个用户的多次操作，而非加锁在方法上锁住所有用户搞成串行
            //取消这个小锁，粒度太小了，导致锁于事务前释放，可能修改不安全
            //判断是否存在
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("用户已经购买过一次！");
                return;
            }
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足!");
                return;
            }
            //因为传来的参数就代表着订单信息voucherOrder,不用再创建订单了，同时不需要订单id返回值
            save(voucherOrder);
        }finally {

        }
    }
}
