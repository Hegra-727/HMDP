package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    //----------------无法依赖spring注入，手动实现对象注入------------------
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler)throws Exception{

        // TODO 获取token
        String token=request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // TODO Redis获取用户
        Map<Object,Object> userMap=stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY+token);
        if(userMap.isEmpty()){
            return true;
        }
        // TODO 查询的hash转UserDTO类型
        UserDTO userDTO= BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        // TODO 将用户信息存入ThreadLocal中
        UserHolder.saveUser(userDTO);
        // TODO 刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler){
        UserHolder.removeUser();
    }
}
