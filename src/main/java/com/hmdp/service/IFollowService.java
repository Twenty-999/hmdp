package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 查询当前用户是否关注了目标用户。
     *
     * @param followUserId 目标用户 ID
     * @return 是否已关注
     */
    Result isFollow(Long followUserId);
}
