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
 * スケジュール機能のDBアクセス層。生JDBC（Connection/PreparedStatement/ResultSet）で書いている。
 * SQLはすべてここに集約する。Service層は業務判断だけを行う。
 *
 * ■ C#（ADO.NET）との対応
 *   SqlConnection   -> java.sql.Connection
 *   SqlCommand      -> java.sql.PreparedStatement
 *   SqlDataReader   -> java.sql.ResultSet
 *   接続文字列      -> DataSource（Spring Bootが自動で用意するBean）
 *
 * SQLExceptionはこのRepository内でキャッチしてRuntimeExceptionに変換し、
 * 呼び出し元（Service/Controller）にthrowsを伝播させない。
 */
@Repository
public class ScheduleRepository {

    private final DataSource dataSource;

    public ScheduleRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---- データの入れ物 ----
    // record は「フィールドとgetterだけを持つ、変更不可のデータの入れ物」を
    // 1行で定義できるJavaの機能。例えば ClassInfo なら、自動で
    // classId()/className()/defaultRoomId() というgetterメソッドが使えるようになる。

    public record ClassInfo(long classId, String className, Long defaultRoomId) {}

    public record Teacher(long teacherId, String teacherName) {}

    public record Room(long roomId, String roomName) {}

    public record ScheduleRow(
            long classId,
            String scheduleDate,
            int checkNo,
            String status,
            String lessonType,
            Long teacherId,
            Long roomId,
            String memo
    ) {}

    // ---- 取得系 ----

    public List<ClassInfo> findActiveClasses() {
        String sql = """
                SELECT class_id, class_name, default_room_id
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """;

        List<ClassInfo> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long classId = rs.getLong("class_id");
                String className = rs.getString("class_name");

                // SQLiteドライバはINTEGER列の値をInteger/Long/nullのいずれかで返すことがあるため、
                // getLong()で直接受けずにObjectとして受け取ってから型を確認して変換している。
                // （default_room_idは空（NULL）の場合があるため、getLong()だと0扱いになってしまい困る）
                Long defaultRoomId = null;
                Object raw = rs.getObject("default_room_id");
                if (raw instanceof Number) {
                    defaultRoomId = ((Number) raw).longValue();
                }

                result.add(new ClassInfo(classId, className, defaultRoomId));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    public List<Teacher> findActiveTeachers() {
        String sql = """
                SELECT teacher_id, teacher_name
                FROM teachers
                WHERE is_active = 1
                ORDER BY teacher_id
                """;

        List<Teacher> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long teacherId = rs.getLong("teacher_id");
                String teacherName = rs.getString("teacher_name");
                result.add(new Teacher(teacherId, teacherName));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    public List<Room> findActiveRooms() {
        String sql = """
                SELECT room_id, room_name
                FROM rooms
                WHERE is_active = 1
                ORDER BY room_id
                """;

        List<Room> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long roomId = rs.getLong("room_id");
                String roomName = rs.getString("room_name");
                result.add(new Room(roomId, roomName));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 指定期間（開始日以上、終了日以下）のスケジュールを取得する。
     */
    public List<ScheduleRow> findSchedulesBetween(String startDate, String endDate) {
        String sql = """
                SELECT class_id, schedule_date, check_no, status, lesson_type, teacher_id, room_id, memo
                FROM schedules
                WHERE schedule_date >= ?
                AND schedule_date <= ?
                """;

        List<ScheduleRow> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, startDate);
            stmt.setString(2, endDate);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapScheduleRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * ResultSetの1行をScheduleRowに変換する共通処理。
     * teacher_id / room_id は「休み」の行だとNULLになりうるので、
     * findActiveClasses()のdefault_room_idと同じ理由でObject経由で変換している。
     */
    private ScheduleRow mapScheduleRow(ResultSet rs) throws SQLException {
        Long teacherId = null;
        Object rawTeacher = rs.getObject("teacher_id");
        if (rawTeacher instanceof Number) {
            teacherId = ((Number) rawTeacher).longValue();
        }

        Long roomId = null;
        Object rawRoom = rs.getObject("room_id");
        if (rawRoom instanceof Number) {
            roomId = ((Number) rawRoom).longValue();
        }

        return new ScheduleRow(
                rs.getLong("class_id"),
                rs.getString("schedule_date"),
                rs.getInt("check_no"),
                rs.getString("status"),
                rs.getString("lesson_type"),
                teacherId,
                roomId,
                rs.getString("memo")
        );
    }

    // ---- 重複チェック系 ----
    // ここで数えている件数は、あくまで「登録前に画面へ分かりやすいエラーを出すための事前チェック」。
    // 最終的な排他制御はDBのUNIQUE制約が担っているので、
    // 仮にこのチェックをすり抜けてもUPSERT時にDBが弾いてくれる（二重の安全策）。

    /**
     * 同じ日・同じ区分で、自クラス以外に同じ教師が登録済みかを数える
     */
    public int countTeacherConflict(long teacherId, String scheduleDate, int checkNo, long excludeClassId) {
        String sql = """
                SELECT COUNT(*)
                FROM schedules
                WHERE teacher_id = ?
                AND schedule_date = ?
                AND check_no = ?
                AND class_id <> ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, teacherId);
            stmt.setString(2, scheduleDate);
            stmt.setInt(3, checkNo);
            stmt.setLong(4, excludeClassId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 同じ日・同じ区分で、自クラス以外に同じ場所が登録済みかを数える
     */
    public int countRoomConflict(long roomId, String scheduleDate, int checkNo, long excludeClassId) {
        String sql = """
                SELECT COUNT(*)
                FROM schedules
                WHERE room_id = ?
                AND schedule_date = ?
                AND check_no = ?
                AND class_id <> ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, roomId);
            stmt.setString(2, scheduleDate);
            stmt.setInt(3, checkNo);
            stmt.setLong(4, excludeClassId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- 更新系 ----

    /**
     * スケジュール1コマを登録する。
     * 同じクラス・日付・区分が既にあれば内容を上書きする（UPSERT）。
     * ON CONFLICT (...) DO UPDATE SET ... はSQLiteのUPSERT構文で、
     * 「INSERTしようとして重複したら、代わりにUPDATEする」という意味。
     * excluded.status のようにexcluded.をつけると「今回INSERTしようとしていた値」を指せる。
     *
     * teacherId / roomId はJavaの Long（オブジェクト型）なので、値がnullの場合がある
     * （「休み」の行）。PreparedStatement.setLong()はnullを渡せないため、
     * nullの時だけsetNull()を使い分けている。
     */
    public void upsertSchedule(
            long classId,
            String scheduleDate,
            int checkNo,
            String status,
            String lessonType,
            Long teacherId,
            Long roomId,
            String memo
    ) {
        String sql = """
                INSERT INTO schedules (
                    class_id, schedule_date, check_no, status, lesson_type, teacher_id, room_id, memo
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (class_id, schedule_date, check_no)
                DO UPDATE SET
                    status = excluded.status,
                    lesson_type = excluded.lesson_type,
                    teacher_id = excluded.teacher_id,
                    room_id = excluded.room_id,
                    memo = excluded.memo
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, classId);
            stmt.setString(2, scheduleDate);
            stmt.setInt(3, checkNo);
            stmt.setString(4, status);
            stmt.setString(5, lessonType);

            if (teacherId == null) {
                stmt.setNull(6, java.sql.Types.INTEGER);
            } else {
                stmt.setLong(6, teacherId);
            }

            if (roomId == null) {
                stmt.setNull(7, java.sql.Types.INTEGER);
            } else {
                stmt.setLong(7, roomId);
            }

            stmt.setString(8, memo);

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
