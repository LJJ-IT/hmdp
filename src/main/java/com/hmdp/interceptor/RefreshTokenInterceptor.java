package com.hmdp.interceptor;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.service.impl.UserServiceImpl.SECRET_KEY;
import static com.hmdp.service.impl.UserServiceImpl.USER_ID;


@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    // 在请求处理之前进行调用（Controller方法调用之前）
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.在请求头中获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            return true;
        }
/*        //2.基于token获得redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }

        //5.存在，保存用户信息到ThreadLocal
        //UserHolder.saveUser((UserDTO) user);
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);//保存用户信息到ThreadLocal

        //6.刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);*/
        //2. 基于jwt的token获取用户信息
        try  {
            log.info("jwt校验{}",token);
            Claims claims = JwtUtil.parseJWT(SECRET_KEY, token);
            Object userIdObj = claims.get(USER_ID);
            // 如果为空，说明校验不通过,直接放到下一个
            if (userIdObj == null) {
                return true;
            }
            Long userId = Long.valueOf(userIdObj.toString());
            //4.存在,把用户信息保存到ThreadLocal
            User user = userService.getById(userId); // 查询用户信息
            if (user == null) {
                return true;
            }
            //5.将用户信息转换为 UserDTO 并保存到 ThreadLocal
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            UserHolder.saveUser(userDTO);

            //6.刷新token有效期


        } catch (Exception e) {
          response.setStatus(401);
          return false;
        }

        //7.放行
        return true;
    }



    // 请求处理之后进行调用，但是在视图被渲染之前（Controller方法调用之后）
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
