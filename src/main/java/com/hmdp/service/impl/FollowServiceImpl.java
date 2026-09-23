package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 根据关注关系判断当前用户是否已关注目标用户。
     *
     * @param followUserId 目标用户 ID
     * @return 是否已关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        if (followUserId == null || followUserId <= 0) {
            return Result.fail("目标用户 ID 不合法！");
        }

        // 2. 查询“当前用户关注目标用户”的记录数量
        int count = query()
                .eq("user_id", user.getId())
                .eq("follow_user_id", followUserId)
                .count();

        // 3. 存在记录表示已经关注
        return Result.ok(count > 0);
    }
}
