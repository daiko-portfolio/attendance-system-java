package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 教室（クラス）管理画面のController。
 * 新教室作成は「教室情報」と「最大20人分の生徒情報」を1つのフォームで
 * まとめて送信し、1回の処理で両方登録する作りになっている。
 * 業務判断が無い単純なCRUDなので、Serviceを挟まずClassMasterRepositoryを直接呼んでいる。
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
    public String classCreateSubmit(@RequestParam Map<String, String> allParams) {
        String className = allParams.get("class_name");
        if (className == null || className.isBlank()) {
            return "redirect:/class/create";
        }

        String startDate = allParams.get("start_date");
        String endDate = allParams.get("end_date");

        // フォームには name_1 〜 name_20 という名前で最大20人分の入力欄が用意されている。
        // 空欄の生徒は登録しない（何人入力されたかは事前に分からないため、決め打ちで20回試す）
        List<ClassMasterRepository.StudentEntry> students = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String studentName = allParams.get("name_" + i);

            if (studentName != null && !studentName.isBlank()) {
                students.add(new ClassMasterRepository.StudentEntry(i, studentName));
            }
        }

        classRepository.createClassWithStudents(className, startDate, endDate, students);

        return "redirect:/classes";
    }

    @GetMapping("/classes")
    public String classes(Model model) {
        model.addAttribute("rows", classRepository.findAll());
        return "classes";
    }

    @PostMapping("/classes/update")
    public String classesUpdate(@RequestParam Map<String, String> allParams) {
        String className = allParams.get("class_name");
        if (className == null || className.isBlank()) {
            return "redirect:/classes";
        }

        long classId = Long.parseLong(allParams.get("class_id"));
        String startDate = allParams.get("start_date");
        String endDate = allParams.get("end_date");

        // チェックボックスはHTMLの仕様上、チェックが外れているとそもそも
        // フォームのパラメータに含まれない（"is_active"というキー自体が来ない）。
        // そのため「値が0だったらチェック無し」ではなく、
        // 「キーが存在するかどうか」で判定する必要がある。
        int isActive;
        if (allParams.containsKey("is_active")) {
            isActive = 1;
        } else {
            isActive = 0;
        }

        classRepository.update(classId, className, startDate, endDate, isActive);

        return "redirect:/classes";
    }
}
