package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 教室（クラス）管理画面のController。
 * 新教室作成は「教室情報」と「最大20人分の生徒情報」を1つのフォームで
 * まとめて送信し、1回の処理で両方登録する作りになっている。
 */
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

    /**
     * RowMapperは名前付きクラスで定義する（ラムダ式は使わない）。
     * mapRow()の中でSQLExceptionをcatchしてRuntimeExceptionに変換しているため、
     * メソッド宣言にthrowsを書かずに済んでいる
     * （RowMapperインターフェース自体はthrows SQLExceptionを要求するが、
     *   ここで例外を握って変換すれば、呼び出し元にthrowsを伝える必要が無くなる）。
     */
    private static class ClassRowMapper implements RowMapper<ClassRow> {
        @Override
        public ClassRow mapRow(ResultSet rs, int rowNum) {
            try {
                return new ClassRow(
                        rs.getLong("class_id"),
                        rs.getString("class_name"),
                        rs.getString("start_date"),
                        rs.getString("end_date"),
                        rs.getInt("is_active") != 0
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

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

        // last_insert_rowid() はSQLite独自の関数で、直前のINSERTで採番されたIDを取得できる。
        // これで今作った教室のclass_idを、続けて登録する生徒たちの外部キーとして使える。
        Long classId = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);

        // フォームには name_1 〜 name_20 という名前で最大20人分の入力欄が用意されている。
        // 空欄の生徒は登録しない（何人入力されたかは事前に分からないため、決め打ちで20回試す）
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
                new ClassRowMapper()
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
