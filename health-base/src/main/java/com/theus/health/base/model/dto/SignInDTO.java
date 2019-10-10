package com.theus.health.base.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * @author tangwei
 * @version 2019/7/28
 */
@Data
public class SignInDTO {

    @NotBlank(message = "用户名不可以为空！")
    private String username;

    @NotBlank(message = "密码不可以为空！")
    @Pattern(regexp = "^(\\w){6,18}$",message = "密码应为[A-Za-z0-9_]组成的6-18位字符！")
    private String password;

}
