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
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> LOAD_FOLLOW_CACHE_SCRIPT;

    static {
        LOAD_FOLLOW_CACHE_SCRIPT = new DefaultRedisScript<>();
        LOAD_FOLLOW_CACHE_SCRIPT.setLocation(
                new ClassPathResource("load-follow-cache.lua")
        );
        LOAD_FOLLOW_CACHE_SCRIPT.setResultType(Long.class);
    }

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
            return finishFollowChange(userId);
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
            return finishFollowChange(userId);
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
                return finishFollowChange(userId);
            }

            // 未查到对应关系，不能把所有唯一键冲突都当成成功
            throw e;
        }

        return finishFollowChange(userId);
    }

    /**
     * 确保用户的关注集合已加载到 Redis。
     *
     * @param userId 发起关注的用户 ID
     */
    private void ensureFollowCache(Long userId) {
        String setKey = FOLLOW_SET_KEY + userId;
        String readyKey = FOLLOW_READY_KEY + userId;

        // 1. 已有加载标记，直接使用缓存
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(readyKey))) {
            return;
        }

        // 2. 查询这个用户关注了哪些人
        List<Follow> relations = query()
                .eq("user_id", userId)
                .list();

        // 3. 组装 Lua 参数：有效期 + 被关注用户 ID
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(FOLLOW_CACHE_TTL));

        for (Follow relation : relations) {
            args.add(relation.getFollowUserId().toString());
        }

        // 4. 写入完整集合和已加载标记
        Long result = stringRedisTemplate.execute(
                LOAD_FOLLOW_CACHE_SCRIPT,
                Arrays.asList(setKey, readyKey),
                args.toArray(new String[0])
        );

        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("关注缓存加载失败");
        }
    }

    /**
     * 在关注操作完成后使缓存失效，并返回成功结果。
     *
     * @param userId 发起关注的用户 ID
     * @return 操作成功结果
     */
    private Result finishFollowChange(Long userId) {
        try {
            // 一次删除关注集合及其已加载标记
            stringRedisTemplate.delete(Arrays.asList(
                    FOLLOW_SET_KEY + userId,
                    FOLLOW_READY_KEY + userId
            ));
        } catch (Exception e) {
            // 数据库操作已经完成，记录缓存失效失败
            log.error("关注操作已完成，但缓存失效失败，用户 ID："
                    + userId, e);
        }

        return Result.ok();
    }
}
