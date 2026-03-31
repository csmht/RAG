package com.mark.knowledge.auth.models;

import com.mark.knowledge.auth.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserInfo {
    private String password;
    private List<Role> roles;
}
