package com.airs.backend.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public class SignUpResponse {

    private Long userId;

    private String email;

    private String nickname;

}
