package com.myproject.dto;

import lombok.Data;

@Data
public class UserLoginDTO {
    // 可传用户名或邮箱，若为空则回退到 username
    private String identifier;

    private String username;

    private String password;

    // 登录验证码
    private String captchaId;

    private String captchaCode;
}
