package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.api.R;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 缓存穿透
//        Shop shop=queryWithPassThrough(id);
        /*// 1.redis查询缓存
        String key="cache:shop"+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop=JSONUtil.toBean(shopJson,Shop.class);
            return Result.ok(shop);
        }
        // new.设置null缓存,复用阻挡穿透
        // (前一个判断已经可以获取有效数据了，现在就是逻辑判断null值了)
        if(shopJson!=null){
            return Result.fail("店铺信息不存在");
        }
        // 3.查询数据库
        Shop shop=getById(id);
        // 4.判断数据库是否存在数据
        if(shop==null){
            // new. DB不存在则返回redis设置null值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        // 5.把查询到的结果存入redis实现复用
        stringRedisTemplate.opsForValue().
                set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);*/

        // 互斥锁解决缓存击穿
//        Shop shop=queryWithMutex(id);
        // 逻辑解决缓存击穿
//        Shop shop=queryWithLogicalExpire(id);
        if(shop==null)
        {
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }

    //缓存穿透的解决方案-设置null缓存给予二次未命中，短暂的无效返回值
    public Shop queryWithPassThrough(Long id){
        // 1.redis查询缓存
        String key="cache:shop"+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        // new.设置null缓存,复用阻挡穿透
        // (前一个判断已经可以获取有效数据了，现在就是逻辑判断null值了)
        if(shopJson!=null){
            return null;
        }
        // 3.查询数据库
        Shop shop=getById(id);
        // 4.判断数据库是否存在数据
        if(shop==null){
            // new. DB不存在则返回redis设置null值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 5.把查询到的结果存入redis实现复用
        stringRedisTemplate.opsForValue().
                set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //缓存击穿的解决方案-互斥锁
    public Shop queryWithMutex(Long id){
        // 1.redis查询缓存
        String key="cache:shop"+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        // new.设置null缓存,复用阻挡穿透
        // (前一个判断已经可以获取有效数据了，现在就是逻辑判断null值了)
        if(shopJson!=null){
            return null;
        }
        //-----------缓存均未命中，开启缓存业务重构-----------------
        //注意锁的key和缓存的key不是同一个！
        String lockKey="lock:shop"+id;
        try {
            boolean isLock = tryLock(lockKey);
            /*Boolean flag=tryLock(""+key);
        if(!flag){
            Thread thread=new Thread();
            thread.sleep(10);
        }*/
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //为了防止后续的线程，重复添加redis的该热点key缓存，获取锁成功后要再判断redis数据是否为空
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson,Shop.class);
            }
            // 3.查询数据库
            Shop shop = getById(id);
            //-------------在查数据库而未添加缓存之时设置延迟，造就线程冲突-------
            Thread.sleep(200);
            // 4.判断数据库是否存在数据
            if (shop == null) {
                // new. DB不存在则返回redis设置null值
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5.把查询到的结果存入redis实现复用
            stringRedisTemplate.opsForValue().
                    set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    //缓存击穿的解决方案-逻辑过期
    public Shop queryWithLogicalExpire(Long id){
        // 1.redis查询缓存
        String key="cache:shop"+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中（这里是）
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //建立在设置null值的基础上的后续判定也不需要了

        // 命中，则需要把json反序列化为对象
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        // 判断缓存是否过期
        //未过期，直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 过期，判断锁是否获取
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock=tryLock(lockKey);
        // 未获取锁，返回脏数据
        // 若获取，则开启独立线程查询数据库更新缓存
        if(isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            //另外，如果获取锁成功，需要再判断缓存是否存在，避免重复加数据入redis
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
//                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 3.查询数据库
//        Shop shop=getById(id);
        //不考虑缓存穿透,存null值

        return shop;
    }


    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //预热redis
    public void saveShop2Redis(Long id,Long expireSeconds){
        // 1.查询店铺数据
        Shop shop=getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
