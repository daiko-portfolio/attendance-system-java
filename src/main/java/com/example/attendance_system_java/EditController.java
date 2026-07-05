package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
public class EditController {

    private final JdbcTemplate jdbcTemplate;

    public EditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ClassOption(long classId, String className) {}

    public record EditRow(
            long personId,
            int attendanceNo,
            String name,
            String status,
            String lessonType,
            Integer attendedHours
    ) {}

    @GetMapping("/edit")
    public String edit(
            @RequestParam(name = "date", required = false) String searchDate,
            @RequestParam(name = "check_no", required = false) String checkNo,
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            Model model
    ) {
        List<ClassOption> classes = jdbcTemplate.query(
                """
                SELECT class_id, class_name
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """,
                (rs, rowNum) -> new ClassOption(rs.getLong("class_id"), rs.getString("class_name"))
        );

        List<EditRow> rows = List.of();
        String currentLessonType = "学科";

        if (searchDate != null && !searchDate.isBlank()
                && checkNo != null && !checkNo.isBlank()
                && selectedClassId != null && !selectedClassId.isBlank()) {

            rows = jdbcTemplate.query(
                    """
                    SELECT
                        p.person_id,
                        p.attendance_no,
                        p.name,
                        a.status,
                        a.lesson_type,
                        a.attended_hours
                    FROM persons p
                    JOIN attendance a ON p.person_id = a.person_id
                    WHERE a.attendance_date = ?
                    AND a.check_no = ?
                    AND p.class_id = ?
                    ORDER BY p.attendance_no
                    """,
                    (rs, rowNum) -> new EditRow(
                            rs.getLong("person_id"),
                            rs.getInt("attendance_no"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getString("lesson_type"),
                            (Integer) rs.getObject("attended_hours")
                    ),
                    searchDate, checkNo, selectedClassId
            );

            currentLessonType = rows.isEmpty() ? "" : rows.get(0).lessonType();
        }

        model.addAttribute("classes", classes);
        model.addAttribute("rows", rows);
        model.addAttribute("searchDate", searchDate);
        model.addAttribute("checkNo", checkNo);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("currentLessonType", currentLessonType);

        return "edit";
    }

    @PostMapping("/attendance/update")
    public String update(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String classId = allParams.get("class_id");
        String attendanceDate = allParams.get("attendance_date");
        String checkNo = allParams.get("check_no");
        String lessonType = allParams.get("lesson_type");

        String sql = """
                UPDATE attendance
                SET status = ?, lesson_type = ?, attended_hours = ?
                WHERE attendance_date = ?
                AND check_no = ?
                AND person_id = ?
                """;

        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("attended_hours_")) {
                String personId = key.substring("attended_hours_".length());
                int attendedHours = Integer.parseInt(entry.getValue());
                String status = attendedHours == 0 ? "欠席" : "出席";

                jdbcTemplate.update(sql, status, lessonType, attendedHours, attendanceDate, checkNo, personId);
            }
        }

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
        String sql = """
                DELETE FROM attendance
                WHERE attendance_date = ?
                AND check_no = ?
                AND person_id IN (
                    SELECT person_id FROM persons WHERE class_id = ?
                )
                """;

        jdbcTemplate.update(sql, attendanceDate, checkNo, classId);

        return "redirect:/edit";
    }
}
