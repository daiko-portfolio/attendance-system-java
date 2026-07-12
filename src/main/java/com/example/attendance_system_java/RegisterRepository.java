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
 *
 * ■ SQLExceptionの扱いについて
 * java.sql.SQLExceptionは「チェック例外」で、本来はメソッドに throws SQLException と
 * 書いて呼び出し元に伝える必要がある。ただしこのプロジェクトのルールでは throws を使わず、
 * ここでキャッチしてRuntimeException（実行時例外）に包んで投げ直している。
 * こうすると、呼び出し元（Service/Controller）は throws を書かずに済む。
 * DB接続エラーのような「起きたら普通は復旧しようがないエラー」なので、
 * 呼び出し元に処理を強制せず、そのままアプリを止めてしまう扱いにしている。
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
     * その教室・日付・区分に登録済みの授業スケジュール1コマ分。
     * 出欠登録画面に「この日の授業予定」として参考表示するために使う。
     * 「休み」として登録されている場合は teacherName / roomName / lessonType がnullになる。
     */
    public record ScheduleInfo(
            String status,
            String lessonType,
            String teacherName,
            String roomName,
            String memo
    ) {}

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
     * 選択された教室の、有効な生徒一覧を出席番号順で取得する
     */
    public List<PersonOption> findPersonsByClassId(String classId) {
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
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 指定教室・日付・区分の授業スケジュールを1件取得する。
     * スケジュール未登録ならnullを返す。
     * 教師名・場所名も一緒に表示したいので teachers / rooms をLEFT JOINしている
     * （「休み」の行はteacher_id/room_idがNULLのため、INNER JOINだと行ごと消えてしまう）。
     */
    public ScheduleInfo findScheduleInfo(String classId, String attendanceDate, String checkNo) {
        String sql = """
                SELECT
                    s.status,
                    s.lesson_type,
                    s.memo,
                    t.teacher_name,
                    r.room_name
                FROM schedules s
                LEFT JOIN teachers t ON s.teacher_id = t.teacher_id
                LEFT JOIN rooms r ON s.room_id = r.room_id
                WHERE s.class_id = ?
                AND s.schedule_date = ?
                AND s.check_no = ?
                """;

        ScheduleInfo result = null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, classId);
            stmt.setString(2, attendanceDate);
            stmt.setString(3, checkNo);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    String lessonType = rs.getString("lesson_type");
                    String memo = rs.getString("memo");
                    String teacherName = rs.getString("teacher_name");
                    String roomName = rs.getString("room_name");

                    result = new ScheduleInfo(status, lessonType, teacherName, roomName, memo);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 指定教室・日付・区分で、既に登録済みの授業属性（学科/実技）を取得する。
     * 同じ日付・区分の出欠は同じ授業属性という前提（Edit画面と同じ考え方）で、
     * 該当する行を1件だけ取得すれば十分なので LIMIT 1 を付けている。
     * 該当データが無ければnullを返す。
     */
    public String findCurrentLessonType(String classId, String attendanceDate, String checkNo) {
        String sql = """
                SELECT a.lesson_type
                FROM attendance a
                JOIN persons p ON a.person_id = p.person_id
                WHERE p.class_id = ?
                AND a.attendance_date = ?
                AND a.check_no = ?
                LIMIT 1
                """;

        String result = null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, classId);
            stmt.setString(2, attendanceDate);
            stmt.setString(3, checkNo);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    result = rs.getString("lesson_type");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 指定日・区分で、既に登録済みの出席時間を person_id ごとに取得する。
     * 戻り値のMapに入っていない生徒は「まだ登録されていない」ことを意味する。
     */
    public Map<Long, Integer> findCurrentHours(String attendanceDate, String checkNo) {
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
        } catch (SQLException e) {
            throw new RuntimeException(e);
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
    ) {
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
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
