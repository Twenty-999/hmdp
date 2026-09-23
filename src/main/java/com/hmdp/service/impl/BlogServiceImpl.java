package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_TIME_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
}
