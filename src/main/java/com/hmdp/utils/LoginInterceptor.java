package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录拦截器，检查登录状态并保存当前线程的用户信息。
 */
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 在执行 Controller 前检查登录状态。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 即将执行的处理器，通常对应 Controller 方法
     * @return 已登录返回 true 放行，未登录返回 false 拦截
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 仅获取已有会话，避免为未登录请求创建新会话
        HttpSession session = request.getSession(false);
        User user = session == null ? null : (User) session.getAttribute("user");

        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 保存必要的用户字段，供当前线程中的业务代码使用
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        UserHolder.saveUser(userDTO);

        return true;
    }

    /**
     * 请求处理完成后清理当前线程的用户信息。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 本次请求的处理器
     * @param ex 处理过程中传递到此处的异常，没有则为 null
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 服务器线程会被复用，必须清理，避免残留用户信息
        UserHolder.removeUser();
    }
}