package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * 教師マスタ管理画面のController。
 *
 * 登録・更新の結果は RedirectAttributes.addFlashAttribute() で画面に伝える。
 * addAttribute（URLに?付きで残る）と違い、addFlashAttributeはリダイレクト先で1回表示されたら
 * 消えるため、メッセージの表示にはこちらを使う（マスタ管理4画面とも同じ作り）。
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
    public String create(
            @RequestParam("teacher_name") String teacherName,
            RedirectAttributes redirectAttributes
    ) {
        if (teacherName == null || teacherName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "教師名が空欄のため、追加していません。");
            return "redirect:/teachers";
        }

        teacherRepository.insert(teacherName.trim());
        redirectAttributes.addFlashAttribute("successMessage", "教師「" + teacherName.trim() + "」を追加しました。");
        return "redirect:/teachers";
    }

    @PostMapping("/teachers/update")
    public String update(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String teacherName = allParams.get("teacher_name");
        if (teacherName == null || teacherName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "教師名が空欄のため、更新していません。");
            return "redirect:/teachers";
        }

        long teacherId = Long.parseLong(allParams.get("teacher_id"));

        // チェックボックスはチェックが外れているとパラメータ自体に含まれないため、
        // 値ではなくキーの有無で有効/無効を判定する（他のマスタ管理も同様）
        int isActive;
        if (allParams.containsKey("is_active")) {
            isActive = 1;
        } else {
            isActive = 0;
        }

        teacherRepository.update(teacherId, teacherName.trim(), isActive);
        redirectAttributes.addFlashAttribute("successMessage", "教師「" + teacherName.trim() + "」を更新しました。");
        return "redirect:/teachers";
    }
}
