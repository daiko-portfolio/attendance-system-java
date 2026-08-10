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
 * 生徒管理画面のDBアクセス層。生JDBCで書いている。
 * 業務判断（分岐・複数テーブルの組み合わせ）が無い単純なCRUDのため、
 * Serviceを挟まずControllerから直接呼ばれる構成にしている。
 */
@Repository
public class PersonMasterRepository {

    private final DataSource dataSource;

    public PersonMasterRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record ClassOption(long classId, String className) {}

    public record PersonRow(
            long personId,
            String className,
            int attendanceNo,
            String name,
            boolean isActive
    ) {}

    // 一括更新フォームの、生徒1人分の入力
    public record UpdateEntry(long personId, String attendanceNo, String name, int isActive) {}

    public List<ClassOption> findAllClasses() {
        String sql = "SELECT class_id, class_name FROM classes ORDER BY class_id";

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

    /**
     * 検索条件（教室・名前）で絞り込んだ生徒一覧を取得する。
     * 各条件は「指定されていれば絞り込む」という任意条件なので、
     * SQL文をStringBuilderで組み立てながら、対応するパラメータをListに積んでいく。
     */
    public List<PersonRow> findRows(String selectedClassId, String searchName) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    p.person_id,
                    c.class_name,
                    p.attendance_no,
                    p.name,
                    p.is_active
                FROM persons p
                JOIN classes c ON p.class_id = c.class_id
                WHERE 1 = 1
                """);

        List<Object> params = new ArrayList<>();

        if (selectedClassId != null && !selectedClassId.isBlank()) {
            sql.append(" AND p.class_id = ?");
            params.add(selectedClassId);
        }

        if (searchName != null && !searchName.isBlank()) {
            sql.append(" AND p.name LIKE ?");
            params.add("%" + searchName + "%");
        }

        sql.append(" ORDER BY c.class_id, p.attendance_no");

        List<PersonRow> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new PersonRow(
                            rs.getLong("person_id"),
                            rs.getString("class_name"),
                            rs.getInt("attendance_no"),
                            rs.getString("name"),
                            rs.getInt("is_active") != 0
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    /**
     * 「新規生徒追加」フォームの出席番号欄に初期値として入れる番号。
     * 選択中の教室の中で一番大きい出席番号+1を提案する（COALESCEは、
     * まだ生徒が1人もいない教室でMAXがNULLになるのを防ぐための初期値0の指定）
     */
    public int findNextAttendanceNo(String classId) {
        String sql = "SELECT COALESCE(MAX(attendance_no), 0) + 1 FROM persons WHERE class_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, classId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 1;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void create(String classId, String attendanceNo, String name) {
        String sql = """
                INSERT INTO persons (attendance_no, name, class_id, is_active)
                VALUES (?, ?, ?, 1)
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, attendanceNo);
            stmt.setString(2, name);
            stmt.setString(3, classId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 複数の生徒を1回のフォーム送信でまとめて更新する。
     * 接続とPreparedStatementは1回だけ用意して、ループ内では値の差し替えと実行だけを行う。
     */
    public void bulkUpdate(List<UpdateEntry> entries) {
        String sql = """
                UPDATE persons
                SET attendance_no = ?, name = ?, is_active = ?
                WHERE person_id = ?
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (UpdateEntry entry : entries) {
                stmt.setString(1, entry.attendanceNo());
                stmt.setString(2, entry.name());
                stmt.setInt(3, entry.isActive());
                stmt.setLong(4, entry.personId());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
