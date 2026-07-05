package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class RegisterController {

    private final JdbcTemplate jdbcTemplate;

    public RegisterController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ClassOption(long classId, String className) {}

    public record PersonOption(long personId, int attendanceNo, String name) {}

    @GetMapping("/register")
    public String register(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "date", required = false) String selectedDate,
            @RequestParam(name = "check_no", required = false, defaultValue = "1") String selectedCheckNo,
            Model model
    ) {
        if (selectedDate == null || selectedDate.isBlank()) {
            selectedDate = LocalDate.now().toString();
        }

        List<ClassOption> classes = jdbcTemplate.query(
                """
                SELECT class_id, class_name
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """,
                (rs, rowNum) -> new ClassOption(rs.getLong("class_id"), rs.getString("class_name"))
        );

        List<PersonOption> persons = List.of();
        String className = null;
        Map<Long, String> currentStatus = new HashMap<>();

        if (selectedClassId != null && !selectedClassId.isBlank()) {

            persons = jdbcTemplate.query(
                    """
                    SELECT person_id, attendance_no, name
                    FROM persons
                    WHERE class_id = ?
                    AND is_active = 1
                    ORDER BY attendance_no
                    """,
                    (rs, rowNum) -> new PersonOption(rs.getLong("person_id"), rs.getInt("attendance_no"), rs.getString("name")),
                    selectedClassId
            );

            for (ClassOption c : classes) {
                if (String.valueOf(c.classId()).equals(selectedClassId)) {
                    className = c.className();
                    break;
                }
            }

            String finalSelectedDate = selectedDate;
            jdbcTemplate.query(
                    """
                    SELECT person_id, status
                    FROM attendance
                    WHERE attendance_date = ?
                    AND check_no = ?
                    """,
                    rs -> {
                        currentStatus.put(rs.getLong("person_id"), rs.getString("status"));
                    },
                    finalSelectedDate, selectedCheckNo
            );
        }

        model.addAttribute("classes", classes);
        model.addAttribute("persons", persons);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("className", className);
        model.addAttribute("today", selectedDate);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("selectedCheckNo", selectedCheckNo);
        model.addAttribute("currentStatus", currentStatus);

        return "register";
    }

    @PostMapping("/attendance/submit")
    public String submitAttendance(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String classId = allParams.get("class_id");
        String attendanceDate = allParams.get("attendance_date");
        String checkNo = allParams.get("check_no");
        String lessonType = allParams.get("lesson_type");

        String sql = """
                INSERT INTO attendance (
                    attendance_date, check_no, person_id, status, lesson_type, attended_hours
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (attendance_date, check_no, person_id)
                DO UPDATE SET
                    status = excluded.status,
                    lesson_type = excluded.lesson_type,
                    attended_hours = excluded.attended_hours
                """;

        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("attended_hours_")) {
                String personId = key.substring("attended_hours_".length());
                int attendedHours = Integer.parseInt(entry.getValue());
                String status = attendedHours == 0 ? "欠席" : "出席";

                jdbcTemplate.update(sql, attendanceDate, checkNo, personId, status, lessonType, attendedHours);
            }
        }

        redirectAttributes.addAttribute("date", attendanceDate);
        redirectAttributes.addAttribute("class_id", classId);

        return "redirect:/list";
    }
}
