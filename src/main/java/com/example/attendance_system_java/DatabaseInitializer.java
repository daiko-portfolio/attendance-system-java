package com.example.attendance_system_java;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 起動時にテーブルが無ければ作る処理。
 * Flask版 app.py の init_db() に相当する。
 * CREATE TABLE IF NOT EXISTS なので既にテーブルがあれば何もしない。
 *
 * ■ Spring Boot初心者向けメモ
 * ・@Component を付けたクラスは、アプリ起動時にSpringが自動でインスタンス化して管理してくれる
 *   （これを「Beanとして登録する」と言う）。Flaskのように自分で new して使うのではなく、
 *   Spring側が必要なタイミングで勝手に作ってくれるイメージ。
 * ・CommandLineRunner は「アプリが起動しきった直後に1回だけ実行してほしい処理」を
 *   表すためのSpring標準インターフェース。これを implements して run() の中身を書くと、
 *   Spring Bootが起動完了時に自動でrun()を呼び出してくれる。
 *   （app.pyの `if __name__ == "__main__": init_db()` の部分に相当）
 * ・コンストラクタで JdbcTemplate を受け取っているのは「コンストラクタインジェクション」という
 *   Springの標準的な書き方。JdbcTemplate自体もSpringが自動生成したBeanで、
 *   Springがこのクラスを作るときに自動的に渡してくれる（自分でnewしなくてよい）。
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Spring Bootの起動が完了すると、このrun()メソッドが自動的に1回だけ呼ばれる
    @Override
    public void run(String... args) {

        // 教師マスタ
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teachers (
                    teacher_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    teacher_name TEXT NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
                """);

        // 場所（物理教室）マスタ
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rooms (
                    room_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    room_name TEXT NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
                """);

        // 教室/訓練クラス
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS classes (
                    class_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_name TEXT NOT NULL,
                    start_date TEXT,
                    end_date TEXT,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    default_room_id INTEGER,
                    FOREIGN KEY(default_room_id) REFERENCES rooms(room_id)
                )
                """);

        // 受講者
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS persons (
                    person_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    attendance_no INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    class_id INTEGER NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    UNIQUE(attendance_no, class_id),
                    FOREIGN KEY(class_id) REFERENCES classes(class_id)
                )
                """);

        // 出欠
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS attendance (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    attendance_date TEXT NOT NULL,
                    check_no INTEGER NOT NULL,
                    person_id INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    lesson_type TEXT NOT NULL DEFAULT '学科',
                    attended_hours INTEGER NOT NULL DEFAULT 3,
                    UNIQUE(attendance_date, check_no, person_id),
                    CHECK(check_no IN (1, 2, 3)),
                    CHECK(lesson_type IN ('学科', '実技')),
                    CHECK(attended_hours IN (0, 1, 2, 3)),
                    FOREIGN KEY(person_id) REFERENCES persons(person_id)
                )
                """);

        // 授業スケジュール
        // UNIQUE制約3つで排他制御を行う
        //   1. 同じクラスの同じ日・同じ区分に授業は1つだけ
        //   2. 同じ教師は同じ日・同じ区分に1か所だけ
        //   3. 同じ場所は同じ日・同じ区分に1クラスだけ
        // SQLiteのUNIQUEはNULL同士を別物として扱うため、
        // 休みの行（teacher_id/room_idがNULL）は何行あっても制約に掛からない
        // check_noの3は「放課後」用（UIは未対応。将来の機能追加に備えてDB側だけ許可している）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schedules (
                    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_id INTEGER NOT NULL,
                    schedule_date TEXT NOT NULL,
                    check_no INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT '通常',
                    lesson_type TEXT,
                    teacher_id INTEGER,
                    room_id INTEGER,
                    memo TEXT,
                    UNIQUE(class_id, schedule_date, check_no),
                    UNIQUE(teacher_id, schedule_date, check_no),
                    UNIQUE(room_id, schedule_date, check_no),
                    CHECK(check_no IN (1, 2, 3)),
                    CHECK(status IN ('通常', '休み')),
                    CHECK(lesson_type IN ('学科', '実技') OR lesson_type IS NULL),
                    FOREIGN KEY(class_id) REFERENCES classes(class_id),
                    FOREIGN KEY(teacher_id) REFERENCES teachers(teacher_id),
                    FOREIGN KEY(room_id) REFERENCES rooms(room_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_schedules_date_check
                ON schedules(schedule_date, check_no)
                """);

        // classes に default_room_id 列が無ければ追加する
        // SQLiteのALTER TABLEはADD COLUMNをサポートしている
        // （古いDBファイルに対して、後から列を1つ増やす操作。既存データは消えない）
        if (!columnExists("classes", "default_room_id")) {
            jdbcTemplate.execute("ALTER TABLE classes ADD COLUMN default_room_id INTEGER");
        }

        // 既にschedulesが作られていてmemo列が無い場合に備えて追加する
        if (!columnExists("schedules", "memo")) {
            jdbcTemplate.execute("ALTER TABLE schedules ADD COLUMN memo TEXT");
        }

        // 教師・場所マスタの編集画面はまだ無いため、
        // テーブルが空の時だけ初期データを入れておく
        Integer teacherCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teachers", Integer.class);
        if (teacherCount != null && teacherCount == 0) {
            jdbcTemplate.update("INSERT INTO teachers (teacher_name) VALUES (?)", "田中");
            jdbcTemplate.update("INSERT INTO teachers (teacher_name) VALUES (?)", "田代");
            jdbcTemplate.update("INSERT INTO teachers (teacher_name) VALUES (?)", "多田");
        }

        Integer roomCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rooms", Integer.class);
        if (roomCount != null && roomCount == 0) {
            jdbcTemplate.update("INSERT INTO rooms (room_name) VALUES (?)", "2F教室");
            jdbcTemplate.update("INSERT INTO rooms (room_name) VALUES (?)", "1F教室");
            jdbcTemplate.update("INSERT INTO rooms (room_name) VALUES (?)", "外部A教室");
        }
    }

    /**
     * 指定テーブルに指定列が存在するかをPRAGMAで確認する。
     * PRAGMA table_info(テーブル名) はSQLite独自のSQLで、
     * そのテーブルの列一覧（列名・型など）を返してくれる。
     * jdbcTemplate.queryForList(...) は、結果の各行を
     * 「列名 -> 値」のMapとして受け取れる、JdbcTemplateの汎用メソッド。
     * RowMapperを自分で書くまでもない単純な取得の時によく使う。
     */
    private boolean columnExists(String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");

        for (Map<String, Object> column : columns) {
            Object name = column.get("name");
            if (name != null && name.toString().equals(columnName)) {
                return true;
            }
        }
        return false;
    }
}
