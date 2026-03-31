package com.mark.knowledge.auth.models;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@AllArgsConstructor
@Data
public class UserConfig {
    private String username;
    private String password;
    private List<String> roles;
}
