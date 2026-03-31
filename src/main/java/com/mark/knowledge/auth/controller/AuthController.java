package com.mark.knowledge.auth.controller;

import com.mark.knowledge.auth.common.Result;
import com.mark.knowledge.auth.dto.JwtResponse;
import com.mark.knowledge.auth.dto.LoginRequest;
import com.mark.knowledge.auth.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<String> authenticateUser(@RequestBody LoginRequest loginRequest) {
        JwtResponse response = authService.login(loginRequest);
        return Result.success(response.getToken());
    }
}
