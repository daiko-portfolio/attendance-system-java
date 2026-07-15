package com.example.attendance_system_java;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * ログインの業務ロジック層。HTTPのことは知らない。
 */
@Service
public class LoginService {

    private final AccountRepository accountRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public LoginService(AccountRepository accountRepository, BCryptPasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * ユーザー名とパスワードで認証する。
     * 成功したらAccountを返し、失敗（ユーザーが居ない／無効／パスワード不一致）ならnullを返す。
     */
    public AccountRepository.Account authenticate(String username, String rawPassword) {
        AccountRepository.Account account = accountRepository.findByUsername(username);

        if (account == null) {
            return null;
        }
        if (!account.isActive()) {
            return null;
        }
        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            return null;
        }
        return account;
    }
}
