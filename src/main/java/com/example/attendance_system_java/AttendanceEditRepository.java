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
 * 出欠編集・削除画面のDBアクセス層。生JDBCで書いている。
 */
@Repository
public class AttendanceEditRepository {

    private final DataSource dataSource;

    public AttendanceEditRepository(DataSource dataSource) {
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

    // 更新1件分（Serviceで出席/欠席の判定を終えた後の状態）
    public record UpdateRow(long personId, String status, int attendedHours) {}

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
                result.add(new ClassOption(rs.getLong("class_id"), rs.getString("class_name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    public List<EditRow> findEditRows(String searchDate, String checkNo, String selectedClassId) {
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

        List<EditRow> result = new ArrayList<>();

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

                    result.add(new EditRow(
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

        return result;
    }

    /**
     * 複数の生徒を1回のフォーム送信でまとめて更新する。
     * 接続とPreparedStatementは1回だけ用意して、ループ内では値の差し替えと実行だけを行う。
     */
    public void updateRows(String attendanceDate, String checkNo, String lessonType, List<UpdateRow> rows) {
        String sql = """
                UPDATE attendance
                SET status = ?, lesson_type = ?, attended_hours = ?
                WHERE attendance_date = ?
                AND check_no = ?
                AND person_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (UpdateRow row : rows) {
                stmt.setString(1, row.status());
                stmt.setString(2, lessonType);
                stmt.setInt(3, row.attendedHours());
                stmt.setString(4, attendanceDate);
                stmt.setString(5, checkNo);
                stmt.setLong(6, row.personId());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteAttendance(String attendanceDate, String checkNo, String classId) {
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
    }
}
