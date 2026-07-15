package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生徒管理画面のController。
 * 一覧の一括更新は "person_id_<id>" のような動的なパラメータ名を使い、
 * RegisterController/EditControllerと同じMap受け取りの仕組みを使っている。
 */
@Controller
public class PersonController {

    private final JdbcTemplate jdbcTemplate;

    public PersonController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ClassOption(long classId, String className) {}

    public record PersonRow(
            long personId,
            String className,
            int attendanceNo,
            String name,
            boolean isActive
    ) {}

    /**
     * RowMapperは名前付きクラスで定義する（ラムダ式は使わない）。
     * SQLExceptionはここでcatchしてRuntimeExceptionに変換し、throwsを外へ伝えない。
     */
    private static class ClassOptionMapper implements RowMapper<ClassOption> {
        @Override
        public ClassOption mapRow(ResultSet rs, int rowNum) {
            try {
                return new ClassOption(rs.getLong("class_id"), rs.getString("class_name"));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class PersonRowMapper implements RowMapper<PersonRow> {
        @Override
        public PersonRow mapRow(ResultSet rs, int rowNum) {
            try {
                return new PersonRow(
                        rs.getLong("person_id"),
                        rs.getString("class_name"),
                        rs.getInt("attendance_no"),
                        rs.getString("name"),
                        rs.getInt("is_active") != 0
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @GetMapping("/persons")
    public String persons(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "name", required = false) String searchName,
            Model model
    ) {
        List<ClassOption> classes = jdbcTemplate.query(
                "SELECT class_id, class_name FROM classes ORDER BY class_id",
                new ClassOptionMapper()
        );

        StringBuilder sql = new StringBuilder("""
                SELECT
                    p.person_id,
                    c.class_name,
                    p.attendance_no,
                    p.name,
                    p.is_active
                FROM persons p
                JOIN classes c ON p.class_id = c.class_id
                WHERE 1 = 1
                """);

        List<Object> params = new ArrayList<>();

        if (selectedClassId != null && !selectedClassId.isBlank()) {
            sql.append(" AND p.class_id = ?");
            params.add(selectedClassId);
        }

        if (searchName != null && !searchName.isBlank()) {
            sql.append(" AND p.name LIKE ?");
            params.add("%" + searchName + "%");
        }

        sql.append(" ORDER BY c.class_id, p.attendance_no");

        List<PersonRow> rows = jdbcTemplate.query(
                sql.toString(),
                new PersonRowMapper(),
                params.toArray()
        );

        // 「新規生徒追加」フォームの出席番号欄に初期値として入れる番号。
        // 選択中の教室の中で一番大きい出席番号+1を提案する（COALESCEは、
        // まだ生徒が1人もいない教室でMAXがNULLになるのを防ぐための初期値0の指定）
        int nextAttendanceNo = 1;

        if (selectedClassId != null && !selectedClassId.isBlank()) {
            Integer next = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(attendance_no), 0) + 1 FROM persons WHERE class_id = ?",
                    Integer.class,
                    selectedClassId
            );
            nextAttendanceNo = next;
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
        jdbcTemplate.update(
                """
                INSERT INTO persons (attendance_no, name, class_id, is_active)
                VALUES (?, ?, ?, 1)
                """,
                attendanceNo, name, classId
        );

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

        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith("person_id_")) {
                String personId = key.substring("person_id_".length());

                String attendanceNo = allParams.get("attendance_no_" + personId);
                String name = allParams.get("name_" + personId);

                int isActive;
                if (allParams.containsKey("is_active_" + personId)) {
                    isActive = 1;
                } else {
                    isActive = 0;
                }

                jdbcTemplate.update(
                        """
                        UPDATE persons
                        SET attendance_no = ?, name = ?, is_active = ?
                        WHERE person_id = ?
                        """,
                        attendanceNo, name, isActive, personId
                );
            }
        }

        redirectAttributes.addAttribute("class_id", selectedClassId);
        redirectAttributes.addAttribute("name", searchName);
        return "redirect:/persons";
    }
}
