package com.example.attendance_system_java;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 教室（クラス）管理画面のDBアクセス層。生JDBCで書いている。
 * 業務判断（分岐・複数テーブルの組み合わせ）が無い単純なCRUDのため、
 * Serviceを挟まずControllerから直接呼ばれる構成にしている。
 */
@Repository
public class ClassMasterRepository {

    private final DataSource dataSource;

    public ClassMasterRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record ClassRow(
            long classId,
            String className,
            String startDate,
            String endDate,
            boolean isActive
    ) {}

    // 新教室作成フォームの、生徒1人分の入力（出席番号＋名前）
    public record StudentEntry(int attendanceNo, String name) {}

    public List<ClassRow> findAll() {
        String sql = """
                SELECT class_id, class_name, start_date, end_date, is_active
                FROM classes
                ORDER BY is_active DESC, end_date DESC, class_id ASC
                """;

        List<ClassRow> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                result.add(new ClassRow(
                        rs.getLong("class_id"),
                        rs.getString("class_name"),
                        rs.getString("start_date"),
                        rs.getString("end_date"),
                        rs.getInt("is_active") != 0
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 教室と、それに紐づく生徒たちをまとめて登録する。
     * 教室のINSERTと生徒のINSERTを「同じ接続」の中で行う。
     * last_insert_rowid() はSQLite独自の関数で「その接続で直前にINSERTされた行のID」を
     * 返すため、別の接続から呼ぶと正しいIDが取れない（同じConnectionを使うのが必須）。
     */
    public void createClassWithStudents(
            String className,
            String startDate,
            String endDate,
            List<StudentEntry> students
    ) {
        try (Connection conn = dataSource.getConnection()) {

            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO classes (class_name, start_date, end_date, is_active)
                    VALUES (?, ?, ?, 1)
                    """)) {
                stmt.setString(1, className);
                stmt.setString(2, startDate);
                stmt.setString(3, endDate);
                stmt.executeUpdate();
            }

            // 今作った教室のclass_idを取得して、生徒たちの外部キーとして使う
            long classId;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                classId = rs.getLong(1);
            }

            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT INTO persons (attendance_no, name, class_id, is_active)
                    VALUES (?, ?, ?, 1)
                    """)) {
                for (StudentEntry student : students) {
                    stmt.setInt(1, student.attendanceNo());
                    stmt.setString(2, student.name());
                    stmt.setLong(3, classId);
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(long classId, String className, String startDate, String endDate, int isActive) {
        String sql = """
                UPDATE classes
                SET class_name = ?, start_date = ?, end_date = ?, is_active = ?
                WHERE class_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, className);
            stmt.setString(2, startDate);
            stmt.setString(3, endDate);
            stmt.setInt(4, isActive);
            stmt.setLong(5, classId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
