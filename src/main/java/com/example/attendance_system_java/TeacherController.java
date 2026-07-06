package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 教師マスタ管理画面のController。
 * Controller / Service / Repository の3層構成で作っている。
 * Controllerはリクエストの受け取りと画面表示だけを担当し、
 * 実際の登録・更新の判断はTeacherServiceに任せる。
 */
@Controller
public class TeacherController {

    private final TeacherService teacherService;

    public TeacherController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping("/teachers")
    public String teachers(Model model) {
        model.addAttribute("teachers", teacherService.findAll());
        return "teachers";
    }

    @PostMapping("/teachers/create")
    public String create(@RequestParam("teacher_name") String teacherName) {
        teacherService.create(teacherName);
        return "redirect:/teachers";
    }

    /**
     * 1行分の更新。チェックボックス(is_active)は、チェックが外れていると
     * そもそもパラメータに含まれないため、キーの有無で有効/無効を判定する。
     */
    @PostMapping("/teachers/update")
    public String update(@RequestParam Map<String, String> allParams) {
        long teacherId = Long.parseLong(allParams.get("teacher_id"));
        String teacherName = allParams.get("teacher_name");
        boolean isActive = allParams.containsKey("is_active");

        teacherService.update(teacherId, teacherName, isActive);
        return "redirect:/teachers";
    }
}
