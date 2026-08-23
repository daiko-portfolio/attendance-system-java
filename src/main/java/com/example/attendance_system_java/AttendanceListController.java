package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * 出欠一覧画面のController。
 * この画面には業務判断が無い（検索条件で絞り込んで表示するだけ）ため、
 * Serviceを挟まずController→AttendanceListRepository直結にしている。
 */
@Controller
public class AttendanceListController {

    private final AttendanceListRepository listRepository;

    public AttendanceListController(AttendanceListRepository listRepository) {
        this.listRepository = listRepository;
    }

    @GetMapping("/list")
    public String list(
            @RequestParam(name = "date", required = false) String searchDate,
            @RequestParam(name = "name", required = false) String searchName,
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "lesson_type", required = false, defaultValue = "") String selectedLessonType,
            Model model
    ) {
        if (searchDate == null || searchDate.isBlank()) {
            searchDate = LocalDate.now().toString();
        }

        List<AttendanceListRepository.ClassOption> classes = listRepository.findActiveClasses();
        List<AttendanceListRepository.AttendanceRow> rows = listRepository.findAttendanceRows(searchDate, selectedClassId, searchName);

        // 補講は毎日あるものではないため、今回の検索結果に1件も無ければ列自体を出さない
        // （午前・午後は必ず出る前提の列なので、こちらは常に出している）
        boolean hasMakeupData = false;
        for (AttendanceListRepository.AttendanceRow row : rows) {
            if (row.makeupHours() != null) {
                hasMakeupData = true;
                break;
            }
        }

        model.addAttribute("rows", rows);
        model.addAttribute("hasMakeupData", hasMakeupData);
        model.addAttribute("classes", classes);
        model.addAttribute("searchDate", searchDate);
        model.addAttribute("searchName", searchName);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("selectedLessonType", selectedLessonType);

        return "list";
    }
}
