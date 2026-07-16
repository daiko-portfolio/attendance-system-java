package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 教室（クラス）管理画面のController。
 * 新教室作成は「教室情報」と「最大20人分の生徒情報」を1つのフォームで
 * まとめて送信し、1回の処理で両方登録する作りになっている。
 *
 * DBアクセスは生JDBC（Connection/PreparedStatement/ResultSet）で書いている。
 * SQLExceptionはこのクラス内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元にthrowsを伝播させない。
 */
@Controller
public class ClassController {

    private final DataSource dataSource;

    public ClassController(DataSource dataSource) {
        this.dataSource = dataSource;
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

        // 教室のINSERTと、その教室に紐づく生徒のINSERTを「同じ接続」の中で行う。
        // last_insert_rowid() はSQLite独自の関数で「その接続で直前にINSERTされた行のID」を
        // 返すため、別の接続から呼ぶと正しいIDが取れない（同じConnectionを使うのが必須）。
        try (Connection conn = dataSource.getConnection()) {

            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO classes (class_name, start_date, end_date, is_active)
                    VALUES (?, ?, ?, 1)
                    """)) {
                stmt.setString(1, className);
                stmt.setString(2, startDate);
                stmt.setString(3, endDate);
                stmt.executeUpdate();
            }

            // 今作った教室のclass_idを取得して、生徒たちの外部キーとして使う
            long classId;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                classId = rs.getLong(1);
            }

            // フォームには name_1 〜 name_20 という名前で最大20人分の入力欄が用意されている。
            // 空欄の生徒は登録しない（何人入力されたかは事前に分からないため、決め打ちで20回試す）
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO persons (attendance_no, name, class_id, is_active)
                    VALUES (?, ?, ?, 1)
                    """)) {
                for (int i = 1; i <= 20; i++) {
                    String studentName = allParams.get("name_" + i);

                    if (studentName != null && !studentName.isBlank()) {
                        stmt.setInt(1, i);
                        stmt.setString(2, studentName);
                        stmt.setLong(3, classId);
                        stmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return "redirect:/classes";
    }

    @GetMapping("/classes")
    public String classes(Model model) {
        String sql = """
                SELECT class_id, class_name, start_date, end_date, is_active
                FROM classes
                ORDER BY is_active DESC, end_date DESC, class_id ASC
                """;

        List<ClassRow> rows = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                rows.add(new ClassRow(
                        rs.getLong("class_id"),
                        rs.getString("class_name"),
                        rs.getString("start_date"),
                        rs.getString("end_date"),
                        rs.getInt("is_active") != 0
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

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

        String sql = """
                UPDATE classes
                SET class_name = ?, start_date = ?, end_date = ?, is_active = ?
                WHERE class_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, className);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);
            stmt.setInt(4, isActive);
            stmt.setString(5, classId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return "redirect:/classes";
    }
}
