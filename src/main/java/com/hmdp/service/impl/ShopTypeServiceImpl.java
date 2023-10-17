package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    @Override
    public Result queryList() {
        //获取key--无
        // 1.查询redis
        //List
        String jsonArray=stringRedisTemplate.opsForValue().get("shop-type");
        // 2.判断缓存命中
        List<ShopType> jsonList= JSONUtil.toList(jsonArray,ShopType.class);
        System.out.println(jsonList);
        if(!CollectionUtil.isEmpty(jsonList)){
            return Result.ok(jsonList);
        }
        // 3.查询MySQL
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4.判断DB信息存在
//        if(!CollectionUtil.isEmpty(typeList)){
//            return Result.fail("商铺分类信息不存在");
//        }
        // 5.查询结果添加到redis复用
        stringRedisTemplate.opsForValue().set("shop-type",JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);

    }
}
