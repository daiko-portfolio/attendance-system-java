package com.example.attendance_system_java;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 起動時にテーブルが無ければ作る処理。
 * Flask版 app.py の init_db() に相当する。
 * CREATE TABLE IF NOT EXISTS なので既にテーブルがあれば何もしない。
 *
 * DBファイル（data/attendance.db）はGit管理外で、起動のたびに無ければ自動生成される
 * 使い捨てのファイルなので、既存データを保持したままの列追加（ALTER TABLE ADD COLUMN）は
 * 行わず、列は最初からすべてCREATE TABLEに書いている
 * （列を後から書き足す場合は、この`data`フォルダごと削除してから起動し直せば、
 *  この最新のCREATE TABLE定義で作り直される）。
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
        // default_room_id はスケジュール登録画面での初期の場所の提案に使う。
        // required_academic_hours / required_practical_hours はコースの必要時間（学科/実技）で、
        // 出席率サマリー画面の消化率（分母）に使う。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS classes (
                    class_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_name TEXT NOT NULL,
                    start_date TEXT,
                    end_date TEXT,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    default_room_id INTEGER,
                    required_academic_hours INTEGER NOT NULL DEFAULT 0,
                    required_practical_hours INTEGER NOT NULL DEFAULT 0,
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
        // 休みの行（teacher_id/room_idがNULL）は何行あっても制約に掛からない。
        // check_noの3は「放課後」用（補講で使用）
        // statusの3種類：
        //   通常 … 予定通り実施
        //   休み … そもそも授業日でない（土日・祝日など。teacher_id/room_idはNULL）
        //   休講 … 授業日として予定していたが中止になった（台風など。理由はmemoに必須で残す）。
        //          teacher_id/room_idは「本来の予定」の記録としてNULLにせずそのまま残す。
        //          このアプリでは休講を「その日全クラスを一斉に休講にする」時にしか
        //          使わない運用を前提にしているため、UNIQUE制約に引っかかる心配はない
        //          （もし特定クラスだけ休講にして教師・場所を別クラスへ回す運用を
        //           追加するなら、teacher_id/room_idのUNIQUE制約を見直す必要がある）
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
                    CHECK(status IN ('通常', '休み', '休講')),
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

        // 教師・場所・教室・生徒のデータは、それぞれのマスタ管理画面（/teachers, /rooms, /classes, /persons）
        // から登録できるため、ここでの初期データ投入は行わない。
        // 動作確認用のまとまったデータが欲しい場合は、メニュー画面の「サンプルデータ投入」ボタンを使う
        // （SampleDataService が sample_data.sql の内容を全テーブルへ投入する）。
    }
}
