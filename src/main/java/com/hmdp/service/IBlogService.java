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
}
