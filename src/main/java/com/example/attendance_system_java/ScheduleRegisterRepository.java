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
 * スケジュール機能のDBアクセス層。生JDBCで書いている（書き方の詳細はAttendanceRegisterRepository参照）。
 * SQLはすべてここに集約する。Service層は業務判断だけを行う。
 *
 * このプロジェクトで唯一、JDBCのトランザクション（setAutoCommit/commit/rollback）を
 * 明示的に使っているRepository。詳細はreplaceWeekSchedules()のコメントを参照。
 */
@Repository
public class ScheduleRegisterRepository {

    private final DataSource dataSource;

    public ScheduleRegisterRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---- データの入れ物 ----

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

                // default_room_idは未設定（NULL）の場合があり、getLong()だと0扱いになってしまう。
                // さらにSQLiteドライバはINTEGER列をInteger/Longどちらで返すか決まっていないため、
                // Objectで受けてからNumberに揃えて変換している
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

    // ---- 更新系 ----

    /**
     * 1週間分のスケジュールを「消してから入れ直す」方式でまとめて登録する。
     *
     * ■ なぜDELETE→INSERTなのか
     * 画面は「その週×対象クラスの全コマ」を毎回まるごと送信してくるので、
     * DB側もその範囲をまるごと入れ替えるのが一番単純で、画面と食い違わない。
     * 1コマずつUPSERTする方式だと、DBに残っている古い行（この送信で上書きされる予定の行）が
     * 重複チェックに引っかかり、「教師を別クラスへ付け替える」操作が誤って拒否される問題があった。
     *
     * ■ トランザクションについて（重要）
     * DELETEした後にINSERTが失敗すると「消しただけ」の状態になってしまうため、
     * 全体を1つのトランザクションにする必要がある。
     * JDBCの接続は普通、1文ごとに自動確定（オートコミット）されるので、
     * setAutoCommit(false) で自動確定を止めてから実行し、
     * 全部成功したら commit()、途中で失敗したら rollback() で巻き戻す。
     * （C#のADO.NETで言う SqlTransaction を使った書き方に相当。
     * 　Springの@Transactionalはこのプロジェクトのような「自分でgetConnection()する生JDBC」には
     * 　効かないため、トランザクションもJDBCの機能で明示的に書いている）
     *
     * 途中でUNIQUE制約違反（フォーム外のクラスとの教師・場所の衝突など）が起きた場合も、
     * rollbackによってDELETE分も含めて全部取り消され、DBは登録前の状態のまま残る。
     */
    public void replaceWeekSchedules(
            List<Long> classIds,
            String startDate,
            String endDate,
            List<ScheduleRow> rows
    ) {
        // クラスIDの数だけ ? を並べたIN句を組み立てる（例: class_id IN (?, ?, ?)）
        StringBuilder deleteSql = new StringBuilder("""
                DELETE FROM schedules
                WHERE schedule_date >= ?
                AND schedule_date <= ?
                AND class_id IN (
                """);
        for (int i = 0; i < classIds.size(); i++) {
            if (i > 0) {
                deleteSql.append(", ");
            }
            deleteSql.append("?");
        }
        deleteSql.append(")");

        String insertSql = """
                INSERT INTO schedules (
                    class_id, schedule_date, check_no, status, lesson_type, teacher_id, room_id, memo
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection()) {

            // ここからトランザクション開始（1文ごとの自動確定を止める）
            conn.setAutoCommit(false);

            try {
                // 1. 対象週×対象クラスの既存行をまとめて削除する
                try (PreparedStatement stmt = conn.prepareStatement(deleteSql.toString())) {
                    stmt.setString(1, startDate);
                    stmt.setString(2, endDate);
                    for (int i = 0; i < classIds.size(); i++) {
                        stmt.setLong(3 + i, classIds.get(i));
                    }
                    stmt.executeUpdate();
                }

                // 2. 今回の内容を1コマずつINSERTする。
                //    teacherId / roomId はLong（オブジェクト型）なのでnullがありうる（「休み」の行）。
                //    setLong()はnullを渡せないため、nullの時だけsetNull()を使い分けている。
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    for (ScheduleRow row : rows) {
                        stmt.setLong(1, row.classId());
                        stmt.setString(2, row.scheduleDate());
                        stmt.setInt(3, row.checkNo());
                        stmt.setString(4, row.status());
                        stmt.setString(5, row.lessonType());

                        if (row.teacherId() == null) {
                            stmt.setNull(6, java.sql.Types.INTEGER);
                        } else {
                            stmt.setLong(6, row.teacherId());
                        }

                        if (row.roomId() == null) {
                            stmt.setNull(7, java.sql.Types.INTEGER);
                        } else {
                            stmt.setLong(7, row.roomId());
                        }

                        stmt.setString(8, row.memo());

                        stmt.executeUpdate();
                    }
                }

                // 3. ここまで全部成功したら確定
                conn.commit();

            } catch (SQLException e) {
                // 途中で失敗したら、DELETEした分も含めて全部巻き戻す
                conn.rollback();
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
