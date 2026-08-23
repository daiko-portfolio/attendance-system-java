package com.example.attendance_system_java;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 出欠一覧画面のDBアクセス層。生JDBCで書いている（AttendanceRegisterRepositoryと同じ方針）。
 * この画面には「出席/欠席を判定する」のような業務判断が無く、
 * 検索条件を組み立ててSQLを実行するだけなので、Serviceは作らずController直結にしている。
 *
 * SQLException（チェック例外）はこのRepository内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元（Controller）にthrowsを伝播させない方針にしている。
 */
@Repository
public class AttendanceListRepository {

    private final DataSource dataSource;

    public AttendanceListRepository(DataSource dataSource) {
        this.dataSource = dataSource;
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
            Integer afternoonHours,
            String makeupLessonType,
            Integer makeupHours
    ) {

        /**
         * 出席時間（午前・午後どちらにも使える）からCSSクラス名を返す。
         *
         * 「色分けの条件分岐をJava側に置き、テンプレートは結果を参照するだけにする」という
         * このプロジェクトの方針を最初に適用した箇所。
         * Thymeleaf側に条件を書くと三項演算子の入れ子になって読みにくくなるため、
         * ここで文字列を組み立てて、テンプレートでは ${row.statusClass(...)} と書くだけで済ませている。
         * （月次出欠表・出席率サマリー・スケジュール画面にも同じ考え方の
         *  statusClass()/judgeClass()/rowClass()/cellClass() を用意している）
         */
        public String statusClass(Integer hours) {
            if (hours == null) {
                return "status-none";
            }
            if (hours == 3) {
                return "status-present";
            }
            if (hours == 0) {
                return "status-absent";
            }
            return "status-warning";
        }
    }

    /**
     * 有効な教室一覧を取得する
     */
    public List<ClassOption> findActiveClasses() {
        String sql = """
                SELECT class_id, class_name
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """;

        List<ClassOption> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long classId = rs.getLong("class_id");
                String className = rs.getString("class_name");
                result.add(new ClassOption(classId, className));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 検索条件（日付・教室・名前）で絞り込んだ出欠一覧を取得する。
     * 各条件は「指定されていれば絞り込む」という任意条件なので、
     * SQL文をStringBuilderで組み立てながら、対応するパラメータをList<String>に積んでいく。
     * PreparedStatementの ? には、後から積んだ順番のままsetString()で当てはめる。
     *
     * MAX(CASE WHEN ...) は、1人につき午前・午後で2行に分かれているattendanceテーブルの
     * データを、person_id+日付ごとに1行（午前列・午後列）へまとめる（横持ちに変換する）ためのSQLの書き方。
     */
    public List<AttendanceRow> findAttendanceRows(
            String searchDate,
            String selectedClassId,
            String searchName
    ) {

        StringBuilder sql = new StringBuilder("""
                SELECT
                    c.class_name,
                    p.attendance_no,
                    p.name,
                    a.attendance_date,
                    MAX(CASE WHEN a.check_no = 1 THEN a.lesson_type END) AS morning_lesson_type,
                    MAX(CASE WHEN a.check_no = 1 THEN a.attended_hours END) AS morning_hours,
                    MAX(CASE WHEN a.check_no = 2 THEN a.lesson_type END) AS afternoon_lesson_type,
                    MAX(CASE WHEN a.check_no = 2 THEN a.attended_hours END) AS afternoon_hours,
                    MAX(CASE WHEN a.check_no = 3 THEN a.lesson_type END) AS makeup_lesson_type,
                    MAX(CASE WHEN a.check_no = 3 THEN a.attended_hours END) AS makeup_hours
                FROM attendance a
                JOIN persons p ON a.person_id = p.person_id
                JOIN classes c ON p.class_id = c.class_id
                WHERE 1 = 1
                """);

        List<String> params = new ArrayList<>();

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

        List<AttendanceRow> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            // paramsに積んだ順番どおりに ?(1番目, 2番目, ...) へ当てはめる
            for (int i = 0; i < params.size(); i++) {
                int placeholderIndex = i + 1;
                stmt.setString(placeholderIndex, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    String className = rs.getString("class_name");
                    int attendanceNo = rs.getInt("attendance_no");
                    String name = rs.getString("name");
                    String attendanceDate = rs.getString("attendance_date");
                    String morningLessonType = rs.getString("morning_lesson_type");
                    String afternoonLessonType = rs.getString("afternoon_lesson_type");
                    String makeupLessonType = rs.getString("makeup_lesson_type");

                    // 午前・午後が未登録の場合、attended_hours列はNULLになりうる。
                    // 生JDBCのgetInt()はNULLを0として返してしまうため、
                    // 「未登録(null)」と「0時間(欠席)」をそのままでは区別できない。
                    // ここではgetInt()した直後にwasNull()を呼んで見分けている
                    // （C#のSqlDataReader.IsDBNull()に近い確認方法）。
                    // 他のRepositoryでは、同じ目的でgetObject()の戻り値の型を見る書き方も使っている。
                    int morningHoursValue = rs.getInt("morning_hours");
                    Integer morningHours;
                    if (rs.wasNull()) {
                        morningHours = null;
                    } else {
                        morningHours = morningHoursValue;
                    }

                    int afternoonHoursValue = rs.getInt("afternoon_hours");
                    Integer afternoonHours;
                    if (rs.wasNull()) {
                        afternoonHours = null;
                    } else {
                        afternoonHours = afternoonHoursValue;
                    }

                    int makeupHoursValue = rs.getInt("makeup_hours");
                    Integer makeupHours;
                    if (rs.wasNull()) {
                        makeupHours = null;
                    } else {
                        makeupHours = makeupHoursValue;
                    }

                    result.add(new AttendanceRow(
                            className,
                            attendanceNo,
                            name,
                            attendanceDate,
                            morningLessonType,
                            morningHours,
                            afternoonLessonType,
                            afternoonHours,
                            makeupLessonType,
                            makeupHours
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }
}
