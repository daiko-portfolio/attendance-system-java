package com.example.attendance_system_java;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ログイン・ログアウトのController。
 */
@Controller
public class LoginController {

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @GetMapping("/login")
    public String loginForm(HttpSession session) {
        // 既にログイン済みならフォームを出さずメニューへ
        if (session.getAttribute("accountId") != null) {
            return "redirect:/";
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam String username,
            @RequestParam String password,
            HttpSession session,
            Model model
    ) {
        AccountRepository.Account account = loginService.authenticate(username, password);

        if (account == null) {
            model.addAttribute("errorMessage", "ユーザー名またはパスワードが違います。");
            return "login";
        }

        session.setAttribute("accountId", account.accountId());
        session.setAttribute("role", account.role());
        session.setAttribute("displayName", account.displayName());

        // PRG（Post-Redirect-Get）パターン
        return "redirect:/";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
