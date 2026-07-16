package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 出欠編集・削除画面のController。
 * RegisterControllerと同様、"attended_hours_<person_id>" という
 * 動的な名前のパラメータをMapでまとめて受け取って処理する構成。
 *
 * DBアクセスは生JDBC（Connection/PreparedStatement/ResultSet）で書いている。
 * SQLExceptionはこのクラス内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元にthrowsを伝播させない。
 */
@Controller
public class EditController {

    private final DataSource dataSource;

    public EditController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record ClassOption(long classId, String className) {}

    public record EditRow(
            long personId,
            int attendanceNo,
            String name,
            String status,
            String lessonType,
            Integer attendedHours
    ) {

        /**
         * 出席時間に応じたCSSクラス名を返す（三項演算子ではなくif-elseで判定）。
         */
        public String statusClass() {
            if (attendedHours == null) {
                return "status-none";
            }
            if (attendedHours == 3) {
                return "status-present";
            }
            if (attendedHours == 0) {
                return "status-absent";
            }
            return "status-warning";
        }
    }

    @GetMapping("/edit")
    public String edit(
            @RequestParam(name = "date", required = false) String searchDate,
            @RequestParam(name = "check_no", required = false) String checkNo,
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            Model model
    ) {
        List<ClassOption> classes = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                     SELECT class_id, class_name
                     FROM classes
                     WHERE is_active = 1
                     ORDER BY class_id
                     """);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                classes.add(new ClassOption(rs.getLong("class_id"), rs.getString("class_name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        List<EditRow> rows = new ArrayList<>();
        String currentLessonType = "学科";

        // 日付・区分・教室の3つがすべて選ばれて初めて編集対象を検索する
        // （どれか1つでも未選択なら、まだ検索条件が揃っていないので何も表示しない）
        if (searchDate != null && !searchDate.isBlank()
                && checkNo != null && !checkNo.isBlank()
                && selectedClassId != null && !selectedClassId.isBlank()) {

            String sql = """
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
                    """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, searchDate);
                stmt.setString(2, checkNo);
                stmt.setString(3, selectedClassId);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        // attended_hoursはNULLの可能性があるため、getInt()（NULLだと0になる）ではなく
                        // getObject()で受けてから型を確認して変換する
                        Integer attendedHours = null;
                        Object rawHours = rs.getObject("attended_hours");
                        if (rawHours instanceof Number) {
                            attendedHours = ((Number) rawHours).intValue();
                        }

                        rows.add(new EditRow(
                                rs.getLong("person_id"),
                                rs.getInt("attendance_no"),
                                rs.getString("name"),
                                rs.getString("status"),
                                rs.getString("lesson_type"),
                                attendedHours
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

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

        // 複数の生徒を1回のフォーム送信でまとめて更新するため、
        // 接続とPreparedStatementは1回だけ用意して、ループ内では値の差し替えと実行だけを行う
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (Map.Entry<String, String> entry : allParams.entrySet()) {
                String key = entry.getKey();

                if (key.startsWith("attended_hours_")) {
                    String personId = key.substring("attended_hours_".length());
                    int attendedHours = Integer.parseInt(entry.getValue());

                    String status;
                    if (attendedHours == 0) {
                        status = "欠席";
                    } else {
                        status = "出席";
                    }

                    stmt.setString(1, status);
                    stmt.setString(2, lessonType);
                    stmt.setInt(3, attendedHours);
                    stmt.setString(4, attendanceDate);
                    stmt.setString(5, checkNo);
                    stmt.setString(6, personId);
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, attendanceDate);
            stmt.setString(2, checkNo);
            stmt.setString(3, classId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return "redirect:/edit";
    }
}
