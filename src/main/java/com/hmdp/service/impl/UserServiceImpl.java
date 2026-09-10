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

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;

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
     * @param session 当前会话，本阶段保留参数，方法内暂未使用
     * @return 处理成功或手机号格式错误的结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
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
     * 校验 Redis 中的验证码，完成登录并将用户保存到 Session。
     *
     * @param loginForm 登录表单，包含手机号和验证码
     * @param session 当前 HTTP 会话，用于保存登录用户
     * @return 登录成功或校验失败的结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid((phone))) {
            return Result.fail("手机号格式错误！");
        }

        // 根据手机号读取 Redis 中的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误或已过期！");
        }

        User user = query().eq("phone", phone).one();

        if (user == null) {
            user = createUserWithPhone(phone);
        }

        session.setAttribute("user", user);

        return Result.ok();
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
