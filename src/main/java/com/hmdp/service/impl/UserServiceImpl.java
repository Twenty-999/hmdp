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
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    /**
     * 发送手机验证码
     *
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid((phone))) {
            return Result.fail("手机号格式错误！");
        }

        String code = RandomUtil.randomNumbers(6);

        session.setAttribute("code", code);

        log.debug("发送短信验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 使用验证码完成登录，并将用户信息保存到 Session。
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session 当前 HTTP 会话，用于读取验证码和保存登录用户
     * @return 登录成功或校验失败的结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid((phone))) {
            return Result.fail("手机号格式错误！");
        }

        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误！");
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
