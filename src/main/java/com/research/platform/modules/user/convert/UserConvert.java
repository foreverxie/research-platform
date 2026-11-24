package com.research.platform.modules.user.convert;

import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.vo.UserVO;

public class UserConvert {

    private UserConvert() {
    }

    /**
     * Entity 转 VO
     */
    public static UserVO toVO(User user) {

        if (user == null) {
            return null;
        }

        UserVO vo = new UserVO();

        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());

        return vo;
    }
}