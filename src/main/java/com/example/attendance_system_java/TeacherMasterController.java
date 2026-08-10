package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 教師マスタ管理画面のController。
 * 業務判断が無い単純なCRUDなので、Serviceを挟まずTeacherMasterRepositoryを直接呼んでいる。
 * 「名前が空なら登録・更新しない」というチェックだけはここ（Controller）で行う。
 */
@Controller
public class TeacherMasterController {

    private final TeacherMasterRepository teacherRepository;

    public TeacherMasterController(TeacherMasterRepository teacherRepository) {
        this.teacherRepository = teacherRepository;
    }

    @GetMapping("/teachers")
    public String teachers(Model model) {
        model.addAttribute("teachers", teacherRepository.findAll());
        return "teachers";
    }

    @PostMapping("/teachers/create")
    public String create(@RequestParam("teacher_name") String teacherName) {
        if (teacherName == null || teacherName.isBlank()) {
            return "redirect:/teachers";
        }
        teacherRepository.insert(teacherName.trim());
        return "redirect:/teachers";
    }

    /**
     * 1行分の更新。チェックボックス(is_active)は、チェックが外れていると
     * そもそもパラメータに含まれないため、キーの有無で有効/無効を判定する。
     */
    @PostMapping("/teachers/update")
    public String update(@RequestParam Map<String, String> allParams) {
        String teacherName = allParams.get("teacher_name");
        if (teacherName == null || teacherName.isBlank()) {
            return "redirect:/teachers";
        }

        long teacherId = Long.parseLong(allParams.get("teacher_id"));

        int isActive;
        if (allParams.containsKey("is_active")) {
            isActive = 1;
        } else {
            isActive = 0;
        }

        teacherRepository.update(teacherId, teacherName.trim(), isActive);
        return "redirect:/teachers";
    }
}
