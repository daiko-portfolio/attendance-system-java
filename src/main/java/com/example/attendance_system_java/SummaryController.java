package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 出席率サマリー画面のController。
 * SQL側でSUM/COUNTを使って集計まで済ませてから受け取り、
 * Java側では「集計値を%や日数に変換するだけ」の役割分担にしている。
 *
 * DBアクセスは生JDBC（Connection/PreparedStatement/ResultSet）で書いている。
 * SQLExceptionはこのクラス内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元にthrowsを伝播させない。
 */
@Controller
public class SummaryController {

    private final DataSource dataSource;

    public SummaryController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record ClassOption(long classId, String className) {}

    private record RawSummary(
            int attendanceNo,
            String name,
            int totalAttendedHours,
            int totalSlots,
            int academicAttendedHours,
            int academicSlots,
            int practicalAttendedHours,
            int practicalSlots
    ) {}

    public record SummaryRow(
            int attendanceNo,
            String name,
            int totalAttended, int totalAbsent, int totalMax, double totalRate, String totalJudge,
            int academicAttended, int academicAbsent, int academicMax, double academicRate, String academicJudge,
            int practicalAttended, int practicalAbsent, int practicalMax, double practicalRate, String practicalJudge
    ) {

        /**
         * 判定文字列（安全/注意/危険）からCSSクラス名を返す。
         * 三項演算子ではなくif-elseで判定する。
         */
        public String judgeClass(String judge) {
            if (judge.equals("安全")) {
                return "judge-safe";
            }
            if (judge.equals("注意")) {
                return "judge-warning";
            }
            return "judge-danger";
        }
    }

    // 出席率のしきい値判定：90%以上は安全、80%以上は注意、それ未満は危険
    private static String judge(double rate) {
        if (rate >= 90) return "安全";
        if (rate >= 80) return "注意";
        return "危険";
    }

    // 小数点第1位で四捨五入する（例：66.66... → 66.7）
    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    @GetMapping("/summary")
    public String summary(
            @RequestParam(name = "class_id", required = false) String selectedClassId,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
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

        List<SummaryRow> rows = new ArrayList<>();

        if (selectedClassId != null && !selectedClassId.isBlank()) {

            // 期間（開始日・終了日）は指定された時だけWHERE句に足していく
            StringBuilder sql = new StringBuilder("""
                    SELECT
                        p.attendance_no,
                        p.name,
                        SUM(a.attended_hours) AS total_attended_hours,
                        COUNT(a.id) AS total_slots,
                        SUM(CASE WHEN a.lesson_type = '学科' THEN a.attended_hours ELSE 0 END) AS academic_attended_hours,
                        SUM(CASE WHEN a.lesson_type = '学科' THEN 1 ELSE 0 END) AS academic_slots,
                        SUM(CASE WHEN a.lesson_type = '実技' THEN a.attended_hours ELSE 0 END) AS practical_attended_hours,
                        SUM(CASE WHEN a.lesson_type = '実技' THEN 1 ELSE 0 END) AS practical_slots
                    FROM persons p
                    JOIN attendance a ON p.person_id = a.person_id
                    WHERE p.class_id = ?
                    AND p.is_active = 1
                    """);

            List<Object> params = new ArrayList<>();
            params.add(selectedClassId);

            if (startDate != null && !startDate.isBlank()) {
                sql.append(" AND a.attendance_date >= ?");
                params.add(startDate);
            }

            if (endDate != null && !endDate.isBlank()) {
                sql.append(" AND a.attendance_date <= ?");
                params.add(endDate);
            }

            sql.append(" GROUP BY p.attendance_no, p.name ORDER BY p.attendance_no ASC");

            List<RawSummary> results = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new RawSummary(
                                rs.getInt("attendance_no"),
                                rs.getString("name"),
                                rs.getInt("total_attended_hours"),
                                rs.getInt("total_slots"),
                                rs.getInt("academic_attended_hours"),
                                rs.getInt("academic_slots"),
                                rs.getInt("practical_attended_hours"),
                                rs.getInt("practical_slots")
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            // SQLで集計した「合計出席時間」「コマ数」から、
            // 最大値（コマ数×3h）・欠席時間・出席率をJava側で計算する
            for (RawSummary r : results) {
                int totalAttended = r.totalAttendedHours();
                int totalMax = r.totalSlots() * 3;
                int totalAbsent = totalMax - totalAttended;

                int academicAttended = r.academicAttendedHours();
                int academicMax = r.academicSlots() * 3;
                int academicAbsent = academicMax - academicAttended;

                int practicalAttended = r.practicalAttendedHours();
                int practicalMax = r.practicalSlots() * 3;
                int practicalAbsent = practicalMax - practicalAttended;

                double academicRate;
                if (academicMax > 0) {
                    academicRate = (double) academicAttended / academicMax * 100;
                } else {
                    academicRate = 0;
                }

                double practicalRate;
                if (practicalMax > 0) {
                    practicalRate = (double) practicalAttended / practicalMax * 100;
                } else {
                    practicalRate = 0;
                }

                double totalRate;
                if (totalMax > 0) {
                    totalRate = (double) totalAttended / totalMax * 100;
                } else {
                    totalRate = 0;
                }

                rows.add(new SummaryRow(
                        r.attendanceNo(),
                        r.name(),
                        totalAttended, totalAbsent, totalMax, round1(totalRate), judge(totalRate),
                        academicAttended, academicAbsent, academicMax, round1(academicRate), judge(academicRate),
                        practicalAttended, practicalAbsent, practicalMax, round1(practicalRate), judge(practicalRate)
                ));
            }
        }

        model.addAttribute("classes", classes);
        model.addAttribute("rows", rows);
        model.addAttribute("selectedClassId", selectedClassId);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);

        return "summary";
    }
}
