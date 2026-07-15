package com.example.attendance_system_java;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * パスワードのハッシュ化に使う BCryptPasswordEncoder をBean登録する。
 * spring-security-crypto だけを依存に足しており、Spring Security本体
 * （ログインフォームやフィルターチェーンを自動で有効化する機能）は使っていない。
 * ハッシュ化・照合のユーティリティクラスだけを借りている形。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
