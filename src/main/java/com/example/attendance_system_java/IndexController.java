package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * メニュー画面（トップページ）のController。
 * このアプリで一番シンプルなControllerで、DB操作もなく
 * 単に templates/index.html を返すだけ。
 * @GetMapping("/") はFlaskの @app.route("/") に相当し、
 * ブラウザがトップページ（"/"）にアクセスした時にindex()が呼ばれる。
 */
@Controller
public class IndexController {

    @GetMapping("/")
    public String index(
            @RequestParam(value = "sampleDataLoaded", required = false) Boolean sampleDataLoaded,
            Model model
    ) {
        boolean loaded;
        if (sampleDataLoaded == null) {
            loaded = false;
        } else {
            loaded = sampleDataLoaded;
        }
        model.addAttribute("sampleDataLoaded", loaded);

        // 戻り値の "index" は templates/index.html を指す
        return "index";
    }
}