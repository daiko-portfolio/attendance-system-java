package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 教室（クラス）管理画面のController。
 * 新教室作成は「教室情報」と「最大20人分の生徒情報」を1つのフォームで
 * まとめて送信し、1回の処理で両方登録する作りになっている。
 */
@Controller
public class ClassMasterController {

    private final ClassMasterRepository classRepository;

    public ClassMasterController(ClassMasterRepository classRepository) {
        this.classRepository = classRepository;
    }

    @GetMapping("/class/create")
    public String classCreateForm() {
        return "class_create";
    }

    @PostMapping("/class/create/submit")
    public String classCreateSubmit(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String className = allParams.get("class_name");
        if (className == null || className.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "教室名が空欄のため、作成していません。");
            return "redirect:/class/create";
        }

        String startDate = allParams.get("start_date");
        String endDate = allParams.get("end_date");
        int requiredAcademicHours = parseHoursOrZero(allParams.get("required_academic_hours"));
        int requiredPracticalHours = parseHoursOrZero(allParams.get("required_practical_hours"));

        // フォームには name_1 〜 name_20 という名前で最大20人分の入力欄が用意されている。
        // 空欄の生徒は登録しない（何人入力されたかは事前に分からないため、決め打ちで20回試す）
        List<ClassMasterRepository.StudentEntry> students = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String studentName = allParams.get("name_" + i);

            if (studentName != null && !studentName.isBlank()) {
                students.add(new ClassMasterRepository.StudentEntry(i, studentName));
            }
        }

        classRepository.createClassWithStudents(
                className, startDate, endDate, requiredAcademicHours, requiredPracticalHours, students);

        redirectAttributes.addFlashAttribute("successMessage",
                "教室「" + className + "」を作成しました（生徒" + students.size() + "名）。");
        return "redirect:/classes";
    }

    @GetMapping("/classes")
    public String classes(Model model) {
        model.addAttribute("rows", classRepository.findAll());
        return "classes";
    }

    @PostMapping("/classes/update")
    public String classesUpdate(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String className = allParams.get("class_name");
        if (className == null || className.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "教室名が空欄のため、更新していません。");
            return "redirect:/classes";
        }

        long classId = Long.parseLong(allParams.get("class_id"));
        String startDate = allParams.get("start_date");
        String endDate = allParams.get("end_date");
        int requiredAcademicHours = parseHoursOrZero(allParams.get("required_academic_hours"));
        int requiredPracticalHours = parseHoursOrZero(allParams.get("required_practical_hours"));

        // チェックボックスはチェックが外れているとパラメータ自体に含まれないため、
        // 値ではなくキーの有無で有効/無効を判定する（他のマスタ管理も同様）
        int isActive;
        if (allParams.containsKey("is_active")) {
            isActive = 1;
        } else {
            isActive = 0;
        }

        classRepository.update(
                classId, className, startDate, endDate, isActive,
                requiredAcademicHours, requiredPracticalHours);

        redirectAttributes.addFlashAttribute("successMessage", "教室「" + className + "」を更新しました。");
        return "redirect:/classes";
    }

    /**
     * 必要時間の入力欄を数値に変換する。空欄や数値でない文字列の場合は0として扱う
     * （必要時間はコース進捗の分母に使うだけの補助情報のため、未入力を弾くほど厳密にはしない）。
     */
    private int parseHoursOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
