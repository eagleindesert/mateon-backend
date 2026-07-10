package com.example.mateon.auth.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * JWT subject 가 userId 이므로 username 파라미터로는 userId(문자열)가 넘어온다.
     */
    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        User user = userRepository.findById(Long.valueOf(userId))
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        return org.springframework.security.core.userdetails.User.builder()
                .username(String.valueOf(user.getId()))
                // 소셜 유저는 비밀번호가 없으므로 빈 문자열로 대체 (폼 로그인 미사용).
                .password(user.getPassword() != null ? user.getPassword() : "")
                .authorities("ROLE_USER")
                .build();
    }
}

