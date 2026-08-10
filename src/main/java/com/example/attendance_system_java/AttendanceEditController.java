package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 出欠編集・削除画面のController。
 * AttendanceRegisterControllerと同様、"attended_hours_<person_id>" という
 * 動的な名前のパラメータをMapでまとめて受け取って処理する構成。
 *
 * 一覧表示・削除は業務判断が無いのでAttendanceEditRepositoryを直接呼び、
 * 「出席/欠席の判定」という業務判断があるまとめ更新だけAttendanceEditServiceに任せている。
 */
@Controller
public class AttendanceEditController {

    private final AttendanceEditRepository attendanceEditRepository;
    private final AttendanceEditService attendanceEditService;

    public AttendanceEditController(
            AttendanceEditRepository attendanceEditRepository,
            AttendanceEditService attendanceEditService
    ) {
        this.attendanceEditRepository = attendanceEditRepository;
        this.attendanceEditService = attendanceEditService;
    }

    @GetMapping("/edit")
    public String edit(
            @RequestParam(name = "date", required = false) String searchDate,
            @RequestParam(name = "check_no", required = false) String checkNo,
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            Model model
    ) {
        List<AttendanceEditRepository.ClassOption> classes = attendanceEditRepository.findActiveClasses();

        List<AttendanceEditRepository.EditRow> rows = List.of();
        String currentLessonType = "学科";

        // 日付・区分・教室の3つがすべて選ばれて初めて編集対象を検索する
        // （どれか1つでも未選択なら、まだ検索条件が揃っていないので何も表示しない）
        if (searchDate != null && !searchDate.isBlank()
                && checkNo != null && !checkNo.isBlank()
                && selectedClassId != null && !selectedClassId.isBlank()) {

            rows = attendanceEditRepository.findEditRows(searchDate, checkNo, selectedClassId);

            if (rows.isEmpty()) {
                currentLessonType = "";
            } else {
                currentLessonType = rows.get(0).lessonType();
            }
        }

        // 日付入力欄の初期値。検索済みならその日付、まだ未検索なら今日の日付にする
        // （三項演算子を使わず、テンプレート側では計算済みの値をそのまま表示するだけにする）
        String dateInputValue;
        if (searchDate == null || searchDate.isBlank()) {
            dateInputValue = LocalDate.now().toString();
        } else {
            dateInputValue = searchDate;
        }

        model.addAttribute("classes", classes);
        model.addAttribute("rows", rows);
        model.addAttribute("searchDate", searchDate);
        model.addAttribute("checkNo", checkNo);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("dateInputValue", dateInputValue);
        model.addAttribute("currentLessonType", currentLessonType);

        return "edit";
    }

    /**
     * @RequestParam Map<String, String> allParams と書くと、
     * フォームから送られてきた「name属性 -> 入力値」の組をすべてまとめて受け取れる。
     * 今回は "attended_hours_<person_id>" のように、name属性が生徒IDの組み合わせで
     * 動的に変化する（何人分来るか事前に決め打ちできない）ため、このMapでまとめて受け取っている。
     */
    @PostMapping("/attendance/update")
    public String update(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String classId = allParams.get("class_id");
        String attendanceDate = allParams.get("attendance_date");
        String checkNo = allParams.get("check_no");
        String lessonType = allParams.get("lesson_type");

        List<AttendanceEditService.PersonHours> entries = new ArrayList<>();

        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("attended_hours_")) {
                String personIdText = key.substring("attended_hours_".length());
                long personId = Long.parseLong(personIdText);
                int attendedHours = Integer.parseInt(entry.getValue());

                entries.add(new AttendanceEditService.PersonHours(personId, attendedHours));
            }
        }

        attendanceEditService.updateAttendance(attendanceDate, checkNo, lessonType, entries);

        redirectAttributes.addAttribute("date", attendanceDate);
        redirectAttributes.addAttribute("check_no", checkNo);
        redirectAttributes.addAttribute("class_id", classId);

        return "redirect:/edit";
    }

    @PostMapping("/attendance/delete")
    public String delete(
            @RequestParam("attendance_date") String attendanceDate,
            @RequestParam("check_no") String checkNo,
            @RequestParam("class_id") String classId
    ) {
        attendanceEditRepository.deleteAttendance(attendanceDate, checkNo, classId);

        return "redirect:/edit";
    }
}
