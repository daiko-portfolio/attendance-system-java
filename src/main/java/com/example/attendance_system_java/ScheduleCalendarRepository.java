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
 * スケジュール月次カレンダー画面のDBアクセス層（生JDBC）。
 * 表示専用の参照クエリだけを持つ。
 */
@Repository
public class ScheduleCalendarRepository {

    private final DataSource dataSource;

    public ScheduleCalendarRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record ClassOption(long classId, String className) {}

    public record TeacherOption(long teacherId, String teacherName) {}

    /**
     * カレンダーの1コマ分の生データ。
     * teacherName / roomName / className は、schedulesの各IDを
     * teachers / rooms / classes テーブルとJOINして名前に変換したもの。
     *
     * 教室ベースの一覧（findClassSchedulesInRange）ではteacherNameだけ埋まり、classNameはnullのまま。
     * 教師ベースの一覧（findTeacherSchedulesInRange）ではclassNameだけ埋まり、teacherNameはnullのまま。
     * どちらの一覧も同じ形（ScheduleSlot）にしておくことで、
     * カレンダーの升目を組み立てるServiceのロジックを1つに共通化できる。
     *
     * 「休み」の行では teacher_id / room_id がNULLなので、これらもnullになる。
     */
    public record ScheduleSlot(
            String scheduleDate,
            int checkNo,
            String status,
            String lessonType,
            String className,
            String teacherName,
            String roomName,
            String memo
    ) {}

    /**
     * プルダウン用に、有効な教室（コース）一覧を取得する
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
     * 指定した教室の、指定期間（開始日〜終了日）のスケジュールを取得する。
     * 教師名・場所名を一緒に取りたいので、teachers / rooms を LEFT JOIN している。
     * LEFT JOINにするのは、休みの行（teacher_id/room_idがNULL）でも
     * スケジュール行そのものは取得したいため（INNER JOINだと消えてしまう）。
     */
    public List<ScheduleSlot> findClassSchedulesInRange(String classId, String startDate, String endDate) {
        String sql = """
                SELECT
                    s.schedule_date,
                    s.check_no,
                    s.status,
                    s.lesson_type,
                    s.memo,
                    t.teacher_name,
                    r.room_name
                FROM schedules s
                LEFT JOIN teachers t ON s.teacher_id = t.teacher_id
                LEFT JOIN rooms r ON s.room_id = r.room_id
                WHERE s.class_id = ?
                AND s.schedule_date >= ?
                AND s.schedule_date <= ?
                """;

        List<ScheduleSlot> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, classId);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String scheduleDate = rs.getString("schedule_date");
                    int checkNo = rs.getInt("check_no");
                    String status = rs.getString("status");
                    String lessonType = rs.getString("lesson_type");
                    String memo = rs.getString("memo");
                    String teacherName = rs.getString("teacher_name");
                    String roomName = rs.getString("room_name");

                    result.add(new ScheduleSlot(
                            scheduleDate, checkNo, status, lessonType, null, teacherName, roomName, memo
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * プルダウン用に、有効な教師一覧を取得する
     */
    public List<TeacherOption> findActiveTeachers() {
        String sql = """
                SELECT teacher_id, teacher_name
                FROM teachers
                WHERE is_active = 1
                ORDER BY teacher_id
                """;

        List<TeacherOption> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                long teacherId = rs.getLong("teacher_id");
                String teacherName = rs.getString("teacher_name");
                result.add(new TeacherOption(teacherId, teacherName));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 指定した教師の、指定期間（開始日〜終了日）のスケジュールを取得する。
     * WHERE teacher_id = ? という条件のため、「休み」の行（teacher_id列がNULL）は
     * そもそも対象にならない（NULL = ? は真にならないため）。
     * つまり、教師の目線では「担当がある日だけ」が返ってくる形になり、
     * それ以外の日は自動的に空欄（未登録）としてカレンダーに表示される。
     */
    public List<ScheduleSlot> findTeacherSchedulesInRange(String teacherId, String startDate, String endDate) {
        String sql = """
                SELECT
                    s.schedule_date,
                    s.check_no,
                    s.status,
                    s.lesson_type,
                    s.memo,
                    c.class_name,
                    r.room_name
                FROM schedules s
                LEFT JOIN classes c ON s.class_id = c.class_id
                LEFT JOIN rooms r ON s.room_id = r.room_id
                WHERE s.teacher_id = ?
                AND s.schedule_date >= ?
                AND s.schedule_date <= ?
                """;

        List<ScheduleSlot> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, teacherId);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String scheduleDate = rs.getString("schedule_date");
                    int checkNo = rs.getInt("check_no");
                    String status = rs.getString("status");
                    String lessonType = rs.getString("lesson_type");
                    String memo = rs.getString("memo");
                    String className = rs.getString("class_name");
                    String roomName = rs.getString("room_name");

                    result.add(new ScheduleSlot(
                            scheduleDate, checkNo, status, lessonType, className, null, roomName, memo
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }
}
