package com.example.attendance_system_java;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 出席率サマリー画面のController。
 * SQL側でSUM/COUNTを使って集計まで済ませてから受け取り、
 * Java側では「集計値を%や日数に変換するだけ」の役割分担にしている。
 */
@Controller
public class SummaryController {

    private final JdbcTemplate jdbcTemplate;

    public SummaryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    ) {}

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
        List<ClassOption> classes = jdbcTemplate.query(
                """
                SELECT class_id, class_name
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """,
                (rs, rowNum) -> new ClassOption(rs.getLong("class_id"), rs.getString("class_name"))
        );

        List<SummaryRow> rows = new ArrayList<>();

        if (selectedClassId != null && !selectedClassId.isBlank()) {

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

            List<RawSummary> results = jdbcTemplate.query(
                    sql.toString(),
                    (rs, rowNum) -> new RawSummary(
                            rs.getInt("attendance_no"),
                            rs.getString("name"),
                            rs.getInt("total_attended_hours"),
                            rs.getInt("total_slots"),
                            rs.getInt("academic_attended_hours"),
                            rs.getInt("academic_slots"),
                            rs.getInt("practical_attended_hours"),
                            rs.getInt("practical_slots")
                    ),
                    params.toArray()
            );

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

                double academicRate = academicMax > 0 ? (double) academicAttended / academicMax * 100 : 0;
                double practicalRate = practicalMax > 0 ? (double) practicalAttended / practicalMax * 100 : 0;
                double totalRate = totalMax > 0 ? (double) totalAttended / totalMax * 100 : 0;

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
