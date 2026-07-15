package com.example.attendance_system_java;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * 全ページ共通のログインチェック・権限チェック。
 * ログインしていなければ /login へ強制的に転送する。
 * 対象外のパス（/login自体や静的ファイル）は WebConfig 側で除外設定している。
 *
 * 生徒（STUDENT）はアクセスできるパスを許可リスト方式で絞っている。
 * 新しい画面を追加した時にリストへの追加を忘れても「デフォルトで拒否」になるため、
 * 禁止リスト方式（新しい書き込み系画面を追加するたびに禁止リストへの追加を忘れると
 * 気付かないうちにアクセスできてしまう）よりも安全側に倒れる。
 *
 * postHandle で、ログイン中の role / displayName を毎回Modelに詰めておくことで、
 * 各Controllerがいちいち書かなくても、全ページのヘッダーやメニューで
 * 「誰がログイン中か」「教師だけに見せる項目」を判定できるようにしている。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    // 生徒（STUDENT）がアクセスして良いパスの許可リスト
    private static final String[] STUDENT_ALLOWED_PATHS = {
            "/", "/logout",
            "/list", "/monthly", "/summary",
            "/schedule/monthly", "/schedule/monthly/teacher"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession();

        if (session.getAttribute("accountId") == null) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", request.getContextPath() + "/login");
            return false;
        }

        String role = (String) session.getAttribute("role");
        if ("STUDENT".equals(role) && !isAllowedForStudent(request.getRequestURI())) {
            // sendError()を使うと、Spring Bootの標準エラーページ機構（/errorへの自動転送）に乗るため、
            // templates/error/403.html があればそれを表示してくれる
            try {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "アクセス権がありません");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return false;
        }

        return true;
    }

    private boolean isAllowedForStudent(String path) {
        for (String allowedPath : STUDENT_ALLOWED_PATHS) {
            if (path.equals(allowedPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void postHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            ModelAndView modelAndView
    ) {
        if (modelAndView == null) {
            return;
        }
        HttpSession session = request.getSession();
        modelAndView.addObject("role", session.getAttribute("role"));
        modelAndView.addObject("displayName", session.getAttribute("displayName"));
    }
}
