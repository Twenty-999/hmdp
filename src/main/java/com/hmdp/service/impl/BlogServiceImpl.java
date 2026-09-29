package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.*;

import static cn.hutool.core.util.DesensitizedUtil.userId;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_TIME_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    /**
     * 查询笔记详情，补充作者信息和点赞状态。
     *
     * @param id 笔记 ID
     * @return 笔记详情
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);

        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        fillBlogAuthor(blog);
        fillBlogLikeStatus(blog);

        return Result.ok(blog);
    }

    /**
     * 补充当前登录用户对笔记的点赞状态。
     * 未登录用户默认显示为未点赞。
     *
     * @param blog 待补充点赞状态的笔记
     */
    private void fillBlogLikeStatus(Blog blog) {
        // 1. 默认未点赞
        blog.setIsLike(false);

        // 2. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }

        // 3. 判断用户是否在这篇笔记的点赞集合中
        String key = BLOG_LIKED_TIME_KEY + blog.getId();

        Double score = stringRedisTemplate.opsForZSet()
                .score(key, user.getId().toString());

        // 4. 将查询结果填入返回对象
        blog.setIsLike(score != null);
    }

    /**
     * 根据当前点赞状态，执行点赞或取消点赞。
     *
     * @param id 笔记 ID
     * @return 操作结果
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        // 2. 检查笔记是否存在
        if (id == null || getById(id) == null) {
            return Result.fail("笔记不存在！");
        }

        String key = BLOG_LIKED_TIME_KEY + id;
        String userId = user.getId().toString();

        // 3. 查询当前用户是否点过赞
        Double score = stringRedisTemplate.opsForZSet()
                .score(key, userId);

        if (score == null) {
            // 4. 未点赞：增加数据库点赞数
            boolean updated = update()
                    .setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();

            if (!updated) {
                return Result.fail("点赞失败！");
            }

            // 记录用户及本次点赞的毫秒时间戳
            stringRedisTemplate.opsForZSet().add(
                    key,
                    userId,
                    System.currentTimeMillis()
            );
        } else {
            // 5. 已点赞：减少数据库点赞数，避免减成负数
            boolean updated = update()
                    .setSql("liked = liked - 1")
                    .eq("id", id)
                    .gt("liked", 0)
                    .update();

            if (!updated) {
                return Result.fail("取消点赞失败，请检查点赞数据！");
            }

            // 移除点赞用户
            stringRedisTemplate.opsForZSet().remove(key, userId);
        }

        return Result.ok();
    }

    /**
     * 补充笔记作者的昵称和头像。
     *
     * @param blog 待补充作者信息的笔记
     */
    private void fillBlogAuthor(Blog blog) {
        User author = userService.getById(blog.getUserId());

        if (author != null) {
            blog.setName(author.getNickName());
            blog.setIcon(author.getIcon());
        }
    }

    /**
     * 按点赞数分页查询热门笔记，并补充展示信息。
     *
     * @param current 页码，从 1 开始
     * @return 热门笔记列表
     */
    @Override
    public Result queryHotBlog(Integer current) {
        if (current == null || current < 1) {
            return Result.fail("页码必须大于等于 1！");
        }

        // 1. 按点赞数降序分页查询，点赞数相同时按 ID 降序排列
        Page<Blog> page = query()
                .orderByDesc("liked")
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        // 2. 获取当前页的笔记
        List<Blog> records = page.getRecords();

        // 3. 为每篇笔记补充作者信息和点赞状态
        for (Blog blog : records) {
            fillBlogAuthor(blog);
            fillBlogLikeStatus(blog);
        }

        return Result.ok(records);
    }

    /**
     * 查询最早点赞的五位用户，并保留点赞排序。
     *
     * @param id 笔记 ID
     * @return 点赞用户的公开信息列表
     */
    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null || getById(id) == null) {
            return Result.fail("笔记不存在！");
        }

        // 1. 按点赞时间从早到晚，获取最多五个用户 ID
        String key = BLOG_LIKED_TIME_KEY + id;
        Set<String> members = stringRedisTemplate.opsForZSet()
                .range(key, 0, 4);

        if (members == null || members.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 将字符串 ID 转换为 Long，保留返回顺序
        List<Long> ids = new ArrayList<>();

        for (String member : members) {
            ids.add(Long.valueOf(member));
        }

        // 3. 一次查询这些用户的信息
        List<User> users = userService.listByIds(ids);

        // 数据库查询结果不保证与 ids 的顺序相同
        Map<Long, User> userMap = new HashMap<>();

        for (User user : users) {
            userMap.put(user.getId(), user);
        }

        // 4. 按 Redis 的顺序组装公开信息
        List<UserDTO> result = new ArrayList<>();

        for (Long userId : ids) {
            User user = userMap.get(userId());

            // 用户已不存在时，跳过这条记录
            if (user == null) {
                continue;
            }

            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());

            result.add(dto);
        }

        return Result.ok(result);
    }

    /**
     * 保存笔记，并向当前粉丝分发动态。
     *
     * @param blog 待发布的笔记
     * @return 发布成功后的笔记 ID
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        if (blog == null) {
            return Result.fail("笔记内容不能为空！");
        }

        // 2. 由服务端设置作者和初始状态
        blog.setId(null);
        blog.setUserId(user.getId());
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(null);
        blog.setUpdateTime(null);

        // 3. 保存笔记，数据库生成的 ID 会回填到 blog
        if (!save(blog)) {
            return Result.fail("笔记发布失败！");
        }

        // 同一篇笔记向所有粉丝使用同一个分发时间
        long publishTime = System.currentTimeMillis();
        String blogId = blog.getId().toString();

        // 4. 查询作者的粉丝
        List<Follow> fans;

        try {
            fans = followService.query()
                    .eq("follow_user_id", user.getId())
                    .list();
        } catch (Exception e) {
            // 笔记已经保存，不能让用户误以为发布失败而重复提交
            log.error("笔记已发布，但粉丝查询失败，笔记 ID："
                    + blog.getId(), e);
            return Result.ok(blog.getId());
        }

        // 5. 向每位粉丝的动态列表写入笔记 ID
        for (Follow fan : fans) {
            Long followerId = fan.getUserId();

            try {
                stringRedisTemplate.opsForZSet().add(
                        FEED_KEY + followerId,
                        blogId,
                        publishTime
                );
            } catch (Exception e) {
                // 单个接收者失败，不中断其余粉丝的分发
                log.error("笔记动态分发失败，笔记 ID："
                        + blog.getId() + "，粉丝 ID：" + followerId, e);
            }
        }

        return Result.ok(blog.getId());
    }

    /**
     * 按发布时间从新到旧查询关注动态。
     *
     * @param maxTime 查询时间上界，单位为毫秒
     * @param offset 当前时间边界下已读取的记录数
     * @return 动态列表及滚动游标
     */
    @Override
    public Result queryBlogOfFollow(Long maxTime, Integer offset) {
        // 1. 校验登录状态和分页参数
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }

        if (maxTime == null || maxTime < 0
                || offset == null || offset < 0) {
            return Result.fail("滚动分页参数不合法！");
        }

        // 2. 从当前用户的动态列表中读取最多两条记录
        String key = FEED_KEY + user.getId();

        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                key, 0, maxTime, offset, 2
                        );

        ScrollResult result = new ScrollResult();

        if (tuples == null || tuples.isEmpty()) {
            result.setList(Collections.emptyList());
            result.setMinTime(maxTime);
            result.setOffset(offset);
            return Result.ok(result);
        }

        // 3. 收集笔记 ID，并统计本页最小时间对应的记录数
        List<Long> ids = new ArrayList<>();
        long minTime = -1L;
        int sameTimeCount = 0;

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String value = tuple.getValue();
            Double score = tuple.getScore();

            if (value == null || score == null) {
                throw new IllegalStateException("动态记录缺少笔记 ID 或时间");
            }

            ids.add(Long.valueOf(value));
            long time = score.longValue();

            if (time == minTime) {
                sameTimeCount++;
            } else {
                minTime = time;
                sameTimeCount = 1;
            }
        }

        // 4. 仍停留在原时间边界时，要加上此前已经跳过的数量
        int nextOffset = minTime == maxTime.longValue()
                ? offset + sameTimeCount
                : sameTimeCount;

        // 5. 批量查询笔记
        List<Blog> blogs = listByIds(ids);
        Map<Long, Blog> blogMap = new HashMap<>();

        for (Blog blog : blogs) {
            blogMap.put(blog.getId(), blog);
        }

        // 6. 按 Redis 返回的顺序组装笔记详情
        List<Blog> orderedBlogs = new ArrayList<>();

        for (Long id : ids) {
            Blog blog = blogMap.get(id);

            if (blog == null) {
                continue;
            }

            fillBlogAuthor(blog);
            fillBlogLikeStatus(blog);
            orderedBlogs.add(blog);
        }

        // 7. 返回列表和下一次查询的游标
        result.setList(orderedBlogs);
        result.setMinTime(minTime);
        result.setOffset(nextOffset);

        return Result.ok(result);
    }
}
