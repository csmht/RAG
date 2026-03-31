package com.mark.knowledge.auth.service;

import com.mark.knowledge.auth.config.UserProperties;
import com.mark.knowledge.auth.enums.Role;
import com.mark.knowledge.auth.models.SecurityUser;
import com.mark.knowledge.auth.models.UserConfig;
import com.mark.knowledge.auth.models.UserInfo;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CustomerUserDetailsService implements UserDetailsService {
    private final Map<String, UserInfo> users = new ConcurrentHashMap<>();

    public CustomerUserDetailsService(UserProperties userProperties, PasswordEncoder passwordEncoder) {
        for (UserConfig userConfig : userProperties.getUsers()) {
            String username = userConfig.getUsername();
            String password = userConfig.getPassword();
            String finalPassword;
            if (password.startsWith("$2a$")) {
                finalPassword = password;
            } else {
                finalPassword = passwordEncoder.encode(password);
            }

            List<Role> roles = userConfig.getRoles().stream()
                    .map(Role::valueOf)
                    .toList();

            users.put(username, new UserInfo(finalPassword, roles));
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserInfo userInfo = users.get(username);
        if (userInfo == null) {
            throw new UsernameNotFoundException("用户不存在:" + username);
        }
        return new SecurityUser(username, userInfo.getPassword(), userInfo.getRoles());
    }
}
