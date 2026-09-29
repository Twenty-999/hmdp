package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    /**
     * 发布探店笔记。
     *
     * @param blog 笔记内容
     * @return 发布成功后的笔记 ID
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞或取消点赞。
     *
     * @param id 笔记 ID
     * @return 操作结果
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 分页获取热门笔记。
     *
     * @param current 页码，默认查询第一页
     * @return 热门笔记列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(
            @RequestParam(value = "current", defaultValue = "1")
            Integer current) {

        return blogService.queryHotBlog(current);
    }


    /**
     * 获取探店笔记详情。
     *
     * @param id 笔记 ID
     * @return 笔记详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 获取笔记最早点赞的五位用户。
     *
     * @param id 笔记 ID
     * @return 点赞用户列表
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 滚动查询当前用户的关注动态。
     *
     * @param lastId 上次返回的最小时间，首次传当前毫秒时间戳
     * @param offset 上次返回的偏移量，首次传 0
     * @return 动态列表及下一次查询的游标
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long lastId,
            @RequestParam(value = "offset", defaultValue = "0")
            Integer offset) {

        return blogService.queryBlogOfFollow(lastId, offset);
    }
}
