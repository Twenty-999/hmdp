package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成手机验证码并保存到 Redis，通过日志模拟短信发送。
     *
     * @param phone 接收验证码的手机号
     * @return 处理成功或手机号格式错误的结果
     */
    @Override
    public Result sendCode(String phone) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 2. 生成六位数字验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 按手机号保存验证码，并设置两分钟有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4. 用日志模拟发送短信
        log.debug("发送短信验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 校验验证码，首次登录时自动注册，并将登录信息保存到 Redis。
     *
     * @param loginForm 登录表单，包含手机号和验证码
     * @return 登录成功时返回 Token，校验失败时返回错误信息
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 2. 核对 Redis 中的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误或已过期！");
        }

        // 3. 查询用户，首次登录时自动注册
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 4. 生成随机登录令牌
        String token = UUID.randomUUID().toString().replace("-", "");

        // 5. 只保存识别当前用户所需的字段
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", user.getId().toString());
        if (user.getNickName() != null) {
            userMap.put("nickName", user.getNickName());
        }
        if (user.getIcon() != null) {
            userMap.put("icon", user.getIcon());
        }

        // 6. 将用户信息写入 Redis Hash，并设置有效期
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(
                tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES
        );

        // 7. 将 Token 交给前端，供后续请求携带
        return Result.ok(token);
    }

    /**
     * 根据手机号创建用户并保存到数据库。
     *
     * @param phone 已通过验证码校验的手机号
     * @return 保存成功的用户，包含数据库生成的 ID
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        if (!save(user)) {
            throw new IllegalStateException("创建用户失败");
        }

        return user;
    }
}
