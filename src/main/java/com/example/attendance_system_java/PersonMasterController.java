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
 * 生徒管理画面のController。
 * 一覧の一括更新は "person_id_<id>" のような動的なパラメータ名を使い、
 * AttendanceRegisterController/AttendanceEditControllerと同じMap受け取りの仕組みを使っている。
 */
@Controller
public class PersonMasterController {

    private final PersonMasterRepository personRepository;

    public PersonMasterController(PersonMasterRepository personRepository) {
        this.personRepository = personRepository;
    }

    @GetMapping("/persons")
    public String persons(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "name", required = false) String searchName,
            Model model
    ) {
        List<PersonMasterRepository.ClassOption> classes = personRepository.findAllClasses();
        List<PersonMasterRepository.PersonRow> rows = personRepository.findRows(selectedClassId, searchName);

        int nextAttendanceNo = 1;
        if (selectedClassId != null && !selectedClassId.isBlank()) {
            nextAttendanceNo = personRepository.findNextAttendanceNo(selectedClassId);
        }

        model.addAttribute("classes", classes);
        model.addAttribute("rows", rows);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("searchName", searchName);
        model.addAttribute("nextAttendanceNo", nextAttendanceNo);

        return "persons";
    }

    @PostMapping("/persons/create")
    public String personsCreate(
            @RequestParam("class_id") String classId,
            @RequestParam("attendance_no") String attendanceNo,
            @RequestParam("name") String name,
            RedirectAttributes redirectAttributes
    ) {
        if (name == null || name.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "名前が空欄のため、追加していません。");
        } else {
            personRepository.create(classId, attendanceNo, name);
            redirectAttributes.addFlashAttribute("successMessage", "生徒「" + name + "」を追加しました。");
        }

        redirectAttributes.addAttribute("class_id", classId);
        return "redirect:/persons";
    }

    @PostMapping("/persons/update")
    public String personsUpdate(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String selectedClassId = allParams.get("selected_class_id");
        String searchName = allParams.get("search_name");

        List<PersonMasterRepository.UpdateEntry> entries = new ArrayList<>();
        int skippedCount = 0;

        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("person_id_")) {
                String personId = key.substring("person_id_".length());

                String attendanceNo = allParams.get("attendance_no_" + personId);
                String name = allParams.get("name_" + personId);

                // 名前が空欄の行は更新対象から除く（誤って名前を消してしまった場合の保険）。
                // 一括更新なので、除いた行が何件あったかを数えておき、後でまとめて画面に伝える
                if (name == null || name.isBlank()) {
                    skippedCount++;
                    continue;
                }

                // チェックボックスはチェックが外れているとパラメータ自体に含まれないため、
                // 値ではなくキーの有無で有効/無効を判定する（他のマスタ管理も同様）
                int isActive;
                if (allParams.containsKey("is_active_" + personId)) {
                    isActive = 1;
                } else {
                    isActive = 0;
                }

                entries.add(new PersonMasterRepository.UpdateEntry(Long.parseLong(personId), attendanceNo, name, isActive));
            }
        }

        personRepository.bulkUpdate(entries);

        if (!entries.isEmpty()) {
            redirectAttributes.addFlashAttribute("successMessage", entries.size() + "件の生徒情報を更新しました。");
        }
        if (skippedCount > 0) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "名前が空欄の行が" + skippedCount + "件あったため、その行は更新していません。");
        }

        redirectAttributes.addAttribute("class_id", selectedClassId);
        redirectAttributes.addAttribute("name", searchName);
        return "redirect:/persons";
    }
}
