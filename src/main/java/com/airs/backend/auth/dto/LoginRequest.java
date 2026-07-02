package com.airs.backend.auth.dto;


import com.airs.backend.user.UserPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LoginRequest {


    @NotBlank
    @Size(max = UserPolicy.EMAIL_MAX_LENGTH)
    private String email;

    @NotBlank
    private String password;
}
