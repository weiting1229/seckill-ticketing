package com.seckill.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.auth.mapper.UserMapper;
import com.seckill.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** ADMIN 種子啟動器:僅在設定憑證且帳號不存在時建立 ADMIN(冪等),密碼經雜湊。 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock UserMapper userMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock IdGenerator idGenerator;

    private AdminBootstrap bootstrap(String username, String password) {
        return new AdminBootstrap(userMapper, passwordEncoder, idGenerator, username, password);
    }

    @Test
    void createsAdminWhenConfiguredAndAbsent() {
        when(idGenerator.nextId()).thenReturn(777L);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(userMapper.existsByUsername("root")).thenReturn(false);

        bootstrap("root", "secret123").run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("root");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getId()).isEqualTo(777L);
    }

    @Test
    void skipsWhenCredentialsBlank() {
        bootstrap("", "").run();
        verify(userMapper, never()).existsByUsername(anyString());
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsWhenAdminAlreadyExists() {
        when(userMapper.existsByUsername("root")).thenReturn(true);
        bootstrap("root", "secret123").run();
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
