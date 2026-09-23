package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 用户关注接口。
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 查询当前用户是否关注了目标用户。
     *
     * @param followUserId 目标用户 ID
     * @return 是否已关注
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 关注或取消关注目标用户。
     *
     * @param followUserId 目标用户 ID
     * @param isFollow 是否关注
     * @return 操作结果
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(
            @PathVariable("id") Long followUserId,
            @PathVariable("isFollow") Boolean isFollow) {

        return followService.follow(followUserId, isFollow);
    }
}