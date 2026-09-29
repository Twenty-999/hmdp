package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据 ID 查询探店笔记详情。
     *
     * @param id 笔记 ID
     * @return 笔记详情或不存在的提示
     */
    Result queryBlogById(Long id);

    /**
     * 切换当前用户对笔记的点赞状态。
     *
     * @param id 笔记 ID
     * @return 操作结果
     */
    Result likeBlog(Long id);

    /**
     * 分页查询热门笔记。
     *
     * @param current 页码，从 1 开始
     * @return 笔记列表，包含作者信息和当前用户的点赞状态
     */
    Result queryHotBlog(Integer current);

    /**
     * 查询笔记最早点赞的五位用户。
     *
     * @param id 笔记 ID
     * @return 点赞用户的公开信息列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布笔记，并向粉丝的动态列表分发笔记 ID。
     *
     * @param blog 待发布的笔记
     * @return 发布成功后的笔记 ID
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动查询当前用户的关注动态。
     *
     * @param maxTime 查询时间上界，单位为毫秒
     * @param offset 当前时间边界下已读取的记录数
     * @return 动态列表及下一次查询的游标
     */
    Result queryBlogOfFollow(Long maxTime, Integer offset);
}
