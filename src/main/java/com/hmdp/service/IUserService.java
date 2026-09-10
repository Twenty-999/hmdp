package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送验证码
     */
    Result sendCode(String phone);

    /**
     * 使用手机号和验证码登录。
     *
     * @param loginForm 登录表单，包含手机号和验证码
     * @return 登录成功时返回 Token，否则返回错误信息
     */
    Result login(LoginFormDTO loginForm);
}
