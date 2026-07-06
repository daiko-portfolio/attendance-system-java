package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 出欠一覧画面のController。
 * このクラスのRowMapperは (rs, rowNum) -> new Xxx(...) というラムダ式で書いている。
 * ScheduleRepositoryでは同じ役割を「名前付きクラス」で書いたが、
 * やっていることは同じで、単に書き方（省略した書き方かどうか）が違うだけ。
 */
@Controller
public class ListController {

    private final JdbcTemplate jdbcTemplate;

    public ListController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ClassOption(long classId, String className) {}

    public record AttendanceRow(
            String className,
            int attendanceNo,
            String name,
            String attendanceDate,
            String morningLessonType,
            Integer morningHours,
            String afternoonLessonType,
            Integer afternoonHours
    ) {}

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

        List<ClassOption> classes = jdbcTemplate.query(
                """
                SELECT class_id, class_name
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """,
                (rs, rowNum) -> new ClassOption(rs.getLong("class_id"), rs.getString("class_name"))
        );

        // MAX(CASE WHEN ...) は、1人につき午前・午後で2行に分かれているattendanceテーブルの
        // データを、person_id+日付ごとに1行（午前列・午後列）へまとめる（横持ちに変換する）ためのSQLの書き方。
        // GROUP BYと組み合わせることで「午前の行にはmorning_hoursだけ値が入り、
        // 午後の行の値はNULLになる」→ それをMAXでまとめると、結果的に片方の値だけが残る、という仕組み。
        StringBuilder sql = new StringBuilder("""
                SELECT
                    c.class_name,
                    p.attendance_no,
                    p.name,
                    a.attendance_date,
                    MAX(CASE WHEN a.check_no = 1 THEN a.lesson_type END) AS morning_lesson_type,
                    MAX(CASE WHEN a.check_no = 1 THEN a.attended_hours END) AS morning_hours,
                    MAX(CASE WHEN a.check_no = 2 THEN a.lesson_type END) AS afternoon_lesson_type,
                    MAX(CASE WHEN a.check_no = 2 THEN a.attended_hours END) AS afternoon_hours
                FROM attendance a
                JOIN persons p ON a.person_id = p.person_id
                JOIN classes c ON p.class_id = c.class_id
                WHERE 1 = 1
                """);

        List<Object> params = new ArrayList<>();

        if (searchDate != null && !searchDate.isBlank()) {
            sql.append(" AND a.attendance_date = ?");
            params.add(searchDate);
        }

        if (selectedClassId != null && !selectedClassId.isBlank()) {
            sql.append(" AND p.class_id = ?");
            params.add(selectedClassId);
        }

        if (searchName != null && !searchName.isBlank()) {
            sql.append(" AND p.name LIKE ?");
            params.add("%" + searchName + "%");
        }

        sql.append("""
                GROUP BY c.class_name, p.attendance_no, p.name, a.attendance_date
                ORDER BY c.class_name ASC, p.attendance_no ASC
                """);

        List<AttendanceRow> rows = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new AttendanceRow(
                        rs.getString("class_name"),
                        rs.getInt("attendance_no"),
                        rs.getString("name"),
                        rs.getString("attendance_date"),
                        rs.getString("morning_lesson_type"),
                        (Integer) rs.getObject("morning_hours"),
                        rs.getString("afternoon_lesson_type"),
                        (Integer) rs.getObject("afternoon_hours")
                ),
                params.toArray()
        );

        model.addAttribute("rows", rows);
        model.addAttribute("classes", classes);
        model.addAttribute("searchDate", searchDate);
        model.addAttribute("searchName", searchName);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("selectedLessonType", selectedLessonType);

        return "list";
    }
}
