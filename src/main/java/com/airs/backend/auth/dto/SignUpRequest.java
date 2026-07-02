package com.airs.backend.auth.dto;


import com.airs.backend.user.UserPolicy;
import com.airs.backend.user.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignUpRequest {

    @Email(message = "올바른 이메일 형식이어야 합니다.")
    @NotBlank(message = "이메일은 필수입니다.")
    @Size(max = UserPolicy.EMAIL_MAX_LENGTH, message = "이메일은 50자 이하여야 합니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-={}\\[\\]:;\"'<>,.?/]).+$",
            message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다."
    )
    @Size(
            min = UserPolicy.PASSWORD_MIN_LENGTH,
            max = UserPolicy.PASSWORD_MAX_LENGTH,
            message = "비밀번호는 8자 이상 30자 이하여야 합니다."
    )
    private String password;

    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(
            min = UserPolicy.NICKNAME_MIN_LENGTH,
            max = UserPolicy.NICKNAME_MAX_LENGTH,
            message = "닉네임은 2자 이상 10자 이하여야 합니다."
    )
    private String nickname;

    @NotBlank(message = "전화번호는 필수입니다.")
    @Size(max = 20, message = "전화번호는 20자 이하여야 합니다.")
    private String phone;

    @NotNull(message = "캠퍼스는 필수입니다.")
    private Long campusId;

    @NotNull(message = "역할은 필수입니다.")
    private UserRole role;
}
