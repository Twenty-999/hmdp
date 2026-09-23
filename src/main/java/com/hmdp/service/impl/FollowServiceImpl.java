package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    @Resource
    private IUserService userService;

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

    /**
     * 新增或删除当前用户与目标用户之间的关注关系。
     *
     * @param followUserId 目标用户 ID
     * @param isFollow true 表示关注，false 表示取消关注
     * @return 操作结果
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1. 校验登录状态和参数
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        if (followUserId == null || followUserId <= 0 || isFollow == null) {
            return Result.fail("关注参数不合法！");
        }

        Long userId = user.getId();

        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己！");
        }

        // 2. 取消关注：删除指定关系
        if (!isFollow) {
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));

            // 即使原本没有关系，也已经达到“未关注”状态
            return Result.ok();
        }

        // 3. 关注前，检查目标用户存在
        if (userService.getById(followUserId) == null) {
            return Result.fail("目标用户不存在！");
        }

        // 4. 已经关注时，直接返回成功
        int count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();

        if (count > 0) {
            return Result.ok();
        }

        // 5. 保存新的关注关系
        Follow relation = new Follow();
        relation.setUserId(userId);
        relation.setFollowUserId(followUserId);

        try {
            if (!save(relation)) {
                return Result.fail("关注失败！");
            }
        } catch (DuplicateKeyException e) {
            // 可能有另一个请求刚刚成功创建了同一条关注关系
            int existingCount = query()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
                    .count();

            if (existingCount > 0) {
                // 已达到“关注”状态，本次请求也可以视为成功
                return Result.ok();
            }

            // 未查到对应关系，不能把所有唯一键冲突都当成成功
            throw e;
        }

        return Result.ok();
    }
}
