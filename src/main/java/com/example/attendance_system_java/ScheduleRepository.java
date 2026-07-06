package com.example.attendance_system_java;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * スケジュール機能のDBアクセス層。
 * SQLはすべてここに集約する。Service層は業務判断だけを行う。
 *
 * ■ Spring Boot初心者向けメモ
 * ・@Repository は @Component の仲間で、「このクラスはDBアクセス専用ですよ」という
 *   目印。付けるとSpringが自動でBean化してくれるのは@Componentと同じ。
 *   さらに、DBアクセス時のエラーをSpring共通の例外に変換してくれる効果もある。
 * ・JdbcTemplate は、Spring BootがSQLiteなどのDBに対してSQLを実行するために
 *   用意している部品（Bean）。生のJDBC（java.sql.Connectionなど）を直接使うと
 *   接続の開始・終了処理を毎回自分で書く必要があるが、JdbcTemplateがそれを
 *   肩代わりしてくれる。Flaskで言う `sqlite3.connect()` + カーソル操作をまとめて
 *   簡単にしてくれるもの、とイメージすると分かりやすい。
 */
@Repository
public class ScheduleRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- データの入れ物 ----
    // record は「フィールドとgetterだけを持つ、変更不可のデータの入れ物」を
    // 1行で定義できるJavaの機能。Pythonの dataclass(frozen=True) に近い。
    // 例えば ClassInfo なら、自動で classId()/className()/defaultRoomId() という
    // getterメソッドが使えるようになる（フィールド名()の形で呼び出す）。

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

    // ---- RowMapper（ラムダ式を使わず名前付きクラスで定義）----
    // RowMapperは「SQLの検索結果（ResultSet）の1行を、Javaのオブジェクト1個に変換する」
    // ためのSpring標準インターフェース。他のController（ListControllerなど）では
    //   (rs, rowNum) -> new Xxx(...)
    // というラムダ式で簡潔に書いているが、ここでは読みやすさを優先して
    // 「implements RowMapper<T>」という名前付きのクラスとして書いている。
    // やっていることはラムダ式と全く同じで、mapRow()の中身がその行1件分の変換ロジック。

    private static class ClassInfoMapper implements RowMapper<ClassInfo> {
        @Override
        public ClassInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            // SQLiteドライバはINTEGER列の値をInteger/Long/nullのいずれかで返すことがあるため、
            // getLong()で直接受けずにObjectとして受け取ってから型を確認して変換している。
            // （default_room_idは空（NULL）の場合があるため、getLong()だと0扱いになってしまい困る）
            Long defaultRoomId = null;
            Object raw = rs.getObject("default_room_id");
            if (raw instanceof Number) {
                defaultRoomId = ((Number) raw).longValue();
            }
            return new ClassInfo(rs.getLong("class_id"), rs.getString("class_name"), defaultRoomId);
        }
    }

    private static class TeacherMapper implements RowMapper<Teacher> {
        @Override
        public Teacher mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Teacher(rs.getLong("teacher_id"), rs.getString("teacher_name"));
        }
    }

    private static class RoomMapper implements RowMapper<Room> {
        @Override
        public Room mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Room(rs.getLong("room_id"), rs.getString("room_name"));
        }
    }

    private static class ScheduleRowMapper implements RowMapper<ScheduleRow> {
        @Override
        public ScheduleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            // teacher_id / room_id は「休み」の行だとNULLになりうるので、
            // ClassInfoMapperと同じ理由でObject経由で変換している
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
    }

    // ---- 取得系 ----

    /**
     * jdbcTemplate.query(sql, mapper) は、SQLを実行して結果を全件取得し、
     * 各行に対してmapper.mapRow()を呼び出した結果をListにまとめて返す。
     * SQLに ? のプレースホルダが無い（=パラメータが無い）場合はこの形で呼べる。
     */
    public List<ClassInfo> findActiveClasses() {
        String sql = """
                SELECT class_id, class_name, default_room_id
                FROM classes
                WHERE is_active = 1
                ORDER BY class_id
                """;
        return jdbcTemplate.query(sql, new ClassInfoMapper());
    }

    public List<Teacher> findActiveTeachers() {
        String sql = """
                SELECT teacher_id, teacher_name
                FROM teachers
                WHERE is_active = 1
                ORDER BY teacher_id
                """;
        return jdbcTemplate.query(sql, new TeacherMapper());
    }

    public List<Room> findActiveRooms() {
        String sql = """
                SELECT room_id, room_name
                FROM rooms
                WHERE is_active = 1
                ORDER BY room_id
                """;
        return jdbcTemplate.query(sql, new RoomMapper());
    }

    /**
     * 指定期間（開始日以上、終了日以下）のスケジュールを取得する。
     * SQL文中の ? は、メソッドの第3引数以降（startDate, endDate）に
     * 出現順で自動的に当てはめられる（SQLインジェクション対策にもなる書き方）。
     */
    public List<ScheduleRow> findSchedulesBetween(String startDate, String endDate) {
        String sql = """
                SELECT class_id, schedule_date, check_no, status, lesson_type, teacher_id, room_id, memo
                FROM schedules
                WHERE schedule_date >= ?
                AND schedule_date <= ?
                """;
        return jdbcTemplate.query(sql, new ScheduleRowMapper(), startDate, endDate);
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
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, teacherId, scheduleDate, checkNo, excludeClassId);
        if (count == null) {
            return 0;
        }
        return count;
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
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, roomId, scheduleDate, checkNo, excludeClassId);
        if (count == null) {
            return 0;
        }
        return count;
    }

    // ---- 更新系 ----

    /**
     * スケジュール1コマを登録する。
     * 同じクラス・日付・区分が既にあれば内容を上書きする（UPSERT）。
     * ON CONFLICT (...) DO UPDATE SET ... はSQLiteのUPSERT構文で、
     * 「INSERTしようとして重複したら、代わりにUPDATEする」という意味。
     * excluded.status のようにexcluded.をつけると「今回INSERTしようとしていた値」を指せる。
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
        jdbcTemplate.update(sql, classId, scheduleDate, checkNo, status, lessonType, teacherId, roomId, memo);
    }
}
