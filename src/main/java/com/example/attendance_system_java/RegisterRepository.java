package com.example.attendance_system_java;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出欠登録画面のDBアクセス層。
 * このRepositoryだけ、他画面と違って「生JDBC」（Connection/PreparedStatement/ResultSetを
 * 自分で開いて閉じる書き方）で書いている。JdbcTemplateが裏で自動でやってくれていた
 * 「接続を開く→SQL実行→接続を閉じる」という流れを、ここでは全部自分の目で見える形にしている。
 *
 * ■ C#（ADO.NET）との対応
 *   SqlConnection   -> java.sql.Connection
 *   SqlCommand      -> java.sql.PreparedStatement
 *   SqlDataReader   -> java.sql.ResultSet
 *   接続文字列      -> DataSource（Spring Bootがapplication.propertiesの設定から
 *                      自動で作ってくれるBean。コンストラクタで受け取るだけでよい）
 *
 * ■ try (...) { ... } について（try-with-resources）
 * Connection/PreparedStatement/ResultSetは、使い終わったら必ずclose()する必要がある。
 * try (Connection conn = ...; PreparedStatement stmt = ...; ResultSet rs = ...) { }
 * と書いておくと、tryブロックを抜ける瞬間（正常終了でも例外発生でも）に
 * 自動でclose()が呼ばれる。C#の using(...) { } と同じ役割。
 */
@Repository
public class RegisterRepository {

    private final DataSource dataSource;

    public RegisterRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record ClassOption(long classId, String className) {}

    public record PersonOption(long personId, int attendanceNo, String name) {}

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
     * 選択された教室の、有効な生徒一覧を出席番号順で取得する
     */
    public List<PersonOption> findPersonsByClassId(String classId) throws SQLException {
        String sql = """
                SELECT person_id, attendance_no, name
                FROM persons
                WHERE class_id = ?
                AND is_active = 1
                ORDER BY attendance_no
                """;

        List<PersonOption> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // PreparedStatementの ? に値を当てはめる。番号は1から始まる
            stmt.setString(1, classId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long personId = rs.getLong("person_id");
                    int attendanceNo = rs.getInt("attendance_no");
                    String name = rs.getString("name");
                    result.add(new PersonOption(personId, attendanceNo, name));
                }
            }
        }

        return result;
    }

    /**
     * 指定日・区分で、既に登録済みの出席時間を person_id ごとに取得する。
     * 戻り値のMapに入っていない生徒は「まだ登録されていない」ことを意味する。
     */
    public Map<Long, Integer> findCurrentHours(String attendanceDate, String checkNo) throws SQLException {
        String sql = """
                SELECT person_id, attended_hours
                FROM attendance
                WHERE attendance_date = ?
                AND check_no = ?
                """;

        Map<Long, Integer> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, attendanceDate);
            stmt.setString(2, checkNo);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long personId = rs.getLong("person_id");
                    int attendedHours = rs.getInt("attended_hours");
                    result.put(personId, attendedHours);
                }
            }
        }

        return result;
    }

    /**
     * 1人分の出欠をUPSERTする（既に同じ日付・区分・生徒の行があれば上書き更新する）。
     * ON CONFLICT ... DO UPDATE はSQLiteのUPSERT構文。
     */
    public void upsertAttendance(
            String attendanceDate,
            String checkNo,
            long personId,
            String status,
            String lessonType,
            int attendedHours
    ) throws SQLException {
        String sql = """
                INSERT INTO attendance (
                    attendance_date, check_no, person_id, status, lesson_type, attended_hours
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (attendance_date, check_no, person_id)
                DO UPDATE SET
                    status = excluded.status,
                    lesson_type = excluded.lesson_type,
                    attended_hours = excluded.attended_hours
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, attendanceDate);
            stmt.setString(2, checkNo);
            stmt.setLong(3, personId);
            stmt.setString(4, status);
            stmt.setString(5, lessonType);
            stmt.setInt(6, attendedHours);

            stmt.executeUpdate();
        }
    }
}
