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
 * 出欠一覧画面のDBアクセス層。生JDBCで書いている（RegisterRepositoryと同じ方針）。
 * この画面には「出席/欠席を判定する」のような業務判断が無く、
 * 検索条件を組み立ててSQLを実行するだけなので、Serviceは作らずController直結にしている。
 */
@Repository
public class ListRepository {

    private final DataSource dataSource;

    public ListRepository(DataSource dataSource) {
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
            Integer afternoonHours
    ) {}

    /**
     * 有効な教室一覧を取得する
     */
    public List<ClassOption> findActiveClasses() throws SQLException {
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
    ) throws SQLException {

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

                    // 午前・午後が未登録の場合、attended_hours列はNULLになりうる。
                    // 生JDBCでNULLかどうかを調べるには、getInt()した直後にwasNull()を呼ぶ
                    // （C#のSqlDataReader.IsDBNull()に近い確認方法）。
                    int morningHoursValue = rs.getInt("morning_hours");
                    Integer morningHours = rs.wasNull() ? null : morningHoursValue;

                    int afternoonHoursValue = rs.getInt("afternoon_hours");
                    Integer afternoonHours = rs.wasNull() ? null : afternoonHoursValue;

                    result.add(new AttendanceRow(
                            className,
                            attendanceNo,
                            name,
                            attendanceDate,
                            morningLessonType,
                            morningHours,
                            afternoonLessonType,
                            afternoonHours
                    ));
                }
            }
        }

        return result;
    }
}
