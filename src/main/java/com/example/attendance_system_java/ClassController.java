package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
public class ClassController {

    private final JdbcTemplate jdbcTemplate;

    public ClassController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ClassRow(
            long classId,
            String className,
            String startDate,
            String endDate,
            boolean isActive
    ) {}

    @GetMapping("/class/create")
    public String classCreateForm() {
        return "class_create";
    }

    @PostMapping("/class/create/submit")
    public String classCreateSubmit(@RequestParam Map<String, String> allParams) {
        String className = allParams.get("class_name");
        String startDate = allParams.get("start_date");
        String endDate = allParams.get("end_date");

        jdbcTemplate.update(
                """
                INSERT INTO classes (class_name, start_date, end_date, is_active)
                VALUES (?, ?, ?, 1)
                """,
                className, startDate, endDate
        );

        Long classId = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);

        for (int i = 1; i <= 20; i++) {
            String studentName = allParams.get("name_" + i);

            if (studentName != null && !studentName.isBlank()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO persons (attendance_no, name, class_id, is_active)
                        VALUES (?, ?, ?, 1)
                        """,
                        i, studentName, classId
                );
            }
        }

        return "redirect:/classes";
    }

    @GetMapping("/classes")
    public String classes(Model model) {
        List<ClassRow> rows = jdbcTemplate.query(
                """
                SELECT class_id, class_name, start_date, end_date, is_active
                FROM classes
                ORDER BY is_active DESC, end_date DESC, class_id ASC
                """,
                (rs, rowNum) -> new ClassRow(
                        rs.getLong("class_id"),
                        rs.getString("class_name"),
                        rs.getString("start_date"),
                        rs.getString("end_date"),
                        rs.getInt("is_active") != 0
                )
        );

        model.addAttribute("rows", rows);
        return "classes";
    }

    @PostMapping("/classes/update")
    public String classesUpdate(@RequestParam Map<String, String> allParams) {
        String classId = allParams.get("class_id");
        String className = allParams.get("class_name");
        String startDate = allParams.get("start_date");
        String endDate = allParams.get("end_date");
        int isActive = allParams.containsKey("is_active") ? 1 : 0;

        jdbcTemplate.update(
                """
                UPDATE classes
                SET class_name = ?, start_date = ?, end_date = ?, is_active = ?
                WHERE class_id = ?
                """,
                className, startDate, endDate, isActive, classId
        );

        return "redirect:/classes";
    }
}
