package com.example.attendance_system_java;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * スケジュール登録の業務ロジック層。
 * HTTP（画面）のことは知らず、Controllerから渡された業務データだけを扱う。
 *
 * ■ Spring Boot初心者向けメモ
 * ・@Service は @Component の仲間で、「このクラスは業務ロジック担当ですよ」という
 *   目印。役割を示すためのアノテーションで、動き自体は@Componentと同じ。
 *   Controller（画面の受付）→ Service（業務判断）→ Repository（DB操作）という
 *   3層に分けるのがSpring Bootの定番の構成。
 */
@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;

    public ScheduleService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * 登録対象の1コマ分。
     * cellKey は「classId_dayIndex_checkNo」形式で、エラー時に画面のセルを特定するために使う。
     * dayLabel は「7/6(月) 午前」のようなエラーメッセージ表示用の文字列。
     */
    public record CellEntry(
            String cellKey,
            long classId,
            String className,
            String scheduleDate,
            int checkNo,
            String status,
            String lessonType,
            Long teacherId,
            Long roomId,
            String memo,
            String dayLabel
    ) {}

    /**
     * 登録結果。失敗時はエラーメッセージと、画面で赤くするセルのキー一覧を返す。
     * record ではなく普通のクラスにしているのは、フィールドをそのまま公開せず
     * isSuccess() などのgetterメソッド越しに読ませたかったため（recordでも書けるが、
     * 今回はあえて明示的なgetterメソッドの形にしている）。
     */
    public static class RegisterResult {
        private final boolean success;
        private final List<String> errorMessages;
        private final Set<String> conflictCells;

        public RegisterResult(boolean success, List<String> errorMessages, Set<String> conflictCells) {
            this.success = success;
            this.errorMessages = errorMessages;
            this.conflictCells = conflictCells;
        }

        public boolean isSuccess() {
            return success;
        }

        public List<String> getErrorMessages() {
            return errorMessages;
        }

        public Set<String> getConflictCells() {
            return conflictCells;
        }
    }

    /**
     * 1週間分のスケジュールを一括登録する。
     * 重複が1件でもあれば全体を登録しない（トランザクションで巻き戻す）。
     *
     * ■ @Transactional について
     * このメソッドの中で複数回 upsertSchedule()（=複数回のSQL実行）を行っているが、
     * @Transactional を付けることで「このメソッド全体を1つの取引（トランザクション）」として扱う。
     * もし途中の1件でエラー（例外）が発生すると、それより前に実行した分のSQLもまとめて
     * 取り消され（ロールバック）、DBには何も反映されない。
     * これにより「14コマ中13コマは登録できたが1コマだけ失敗した」という
     * 中途半端な状態を防いでいる（今回のユーザー要望「①週全体を巻き戻す」の実現方法）。
     */
    @Transactional
    public RegisterResult registerWeek(List<CellEntry> entries) {

        List<String> errorMessages = new ArrayList<>();
        Set<String> conflictCells = new HashSet<>();

        // 教師名・場所名をメッセージ表示用に引けるようにしておく
        // （IDだけだとエラーメッセージが「教師ID:2が重複」のようになり分かりにくいため、
        //   先に teacherId -> teacherName の対応表を作っておく）
        Map<Long, String> teacherNames = new HashMap<>();
        for (ScheduleRepository.Teacher teacher : scheduleRepository.findActiveTeachers()) {
            teacherNames.put(teacher.teacherId(), teacher.teacherName());
        }

        Map<Long, String> roomNames = new HashMap<>();
        for (ScheduleRepository.Room room : scheduleRepository.findActiveRooms()) {
            roomNames.put(room.roomId(), room.roomName());
        }

        // ---- チェック1: 送信フォーム内での重複 ----
        // 「今回送られてきた14コマ×クラス数」の中だけで見て、
        // 同じ教師・同じ場所が「同じ日付・同じ区分」に2回以上登場していないかを調べる。
        // 「教師ID_日付_区分」というキー文字列を作り、Mapに既に同じキーがあれば重複、
        // という仕組みで判定している（DBに問い合わせなくても分かるチェック）。
        Map<String, CellEntry> seenTeachers = new HashMap<>();
        Map<String, CellEntry> seenRooms = new HashMap<>();

        for (CellEntry entry : entries) {

            // 「休み」のコマは教師・場所が無いのでチェック対象外
            if (!entry.status().equals("通常")) {
                continue;
            }

            if (entry.teacherId() != null) {
                String teacherKey = entry.teacherId() + "_" + entry.scheduleDate() + "_" + entry.checkNo();

                CellEntry already = seenTeachers.get(teacherKey);
                if (already != null) {
                    // 同じキーが既にMapにあった = 同じ教師が同じ時間帯に2回登場している
                    String teacherName = teacherNames.get(entry.teacherId());
                    errorMessages.add(
                            entry.dayLabel() + "：" + teacherName + "先生が "
                            + already.className() + " と " + entry.className() + " の両方に登録されています"
                    );
                    conflictCells.add(already.cellKey());
                    conflictCells.add(entry.cellKey());
                } else {
                    seenTeachers.put(teacherKey, entry);
                }
            }

            if (entry.roomId() != null) {
                String roomKey = entry.roomId() + "_" + entry.scheduleDate() + "_" + entry.checkNo();

                CellEntry already = seenRooms.get(roomKey);
                if (already != null) {
                    String roomName = roomNames.get(entry.roomId());
                    errorMessages.add(
                            entry.dayLabel() + "：" + roomName + " が "
                            + already.className() + " と " + entry.className() + " で二重予約されています"
                    );
                    conflictCells.add(already.cellKey());
                    conflictCells.add(entry.cellKey());
                } else {
                    seenRooms.put(roomKey, entry);
                }
            }
        }

        // ---- チェック2: DBに登録済みの他クラスとの重複 ----
        // チェック1は「今回送信された内容どうし」の重複だったが、
        // こちらは「以前に登録済みで、既にDBに保存されているスケジュール」との重複を見る。
        // 例：先週Aクラスの担当を決めた後、今週Bクラスで同じ教師・同じ時間帯を
        // 割り当てようとした場合はこちらで引っかかる。
        for (CellEntry entry : entries) {

            if (!entry.status().equals("通常")) {
                continue;
            }

            if (entry.teacherId() != null) {
                int count = scheduleRepository.countTeacherConflict(
                        entry.teacherId(), entry.scheduleDate(), entry.checkNo(), entry.classId());

                if (count > 0) {
                    String teacherName = teacherNames.get(entry.teacherId());
                    errorMessages.add(
                            entry.dayLabel() + "：" + teacherName + "先生は同じ時間帯に別クラスへ登録済みです"
                    );
                    conflictCells.add(entry.cellKey());
                }
            }

            if (entry.roomId() != null) {
                int count = scheduleRepository.countRoomConflict(
                        entry.roomId(), entry.scheduleDate(), entry.checkNo(), entry.classId());

                if (count > 0) {
                    String roomName = roomNames.get(entry.roomId());
                    errorMessages.add(
                            entry.dayLabel() + "：" + roomName + " は同じ時間帯に別クラスが使用予定です"
                    );
                    conflictCells.add(entry.cellKey());
                }
            }
        }

        // 重複が1つでもあれば、ここで処理を打ち切って登録を一切行わない
        // （success=false を返すのでControllerはこの週を保存せず、エラー内容だけ画面へ戻す）
        if (!errorMessages.isEmpty()) {
            return new RegisterResult(false, errorMessages, conflictCells);
        }

        // ---- 登録処理 ----
        // ここまでのチェックを通過した場合のみ実際にINSERT/UPDATEを行う。
        // 万が一チェックをすり抜けるタイミング差（同時アクセスなど）があっても、
        // DB側のUNIQUE制約が最終防衛ラインとして例外を投げてくれる。
        // その例外が発生した場合も、クラス冒頭の @Transactional のおかげで
        // ここまでにupsertした分を含めて全部ロールバックされる。
        for (CellEntry entry : entries) {

            if (entry.status().equals("休み")) {
                // 休みでもメモ（休講理由など）は残す
                scheduleRepository.upsertSchedule(
                        entry.classId(), entry.scheduleDate(), entry.checkNo(),
                        "休み", null, null, null, entry.memo()
                );
            } else {
                scheduleRepository.upsertSchedule(
                        entry.classId(), entry.scheduleDate(), entry.checkNo(),
                        "通常", entry.lessonType(), entry.teacherId(), entry.roomId(), entry.memo()
                );
            }
        }

        return new RegisterResult(true, errorMessages, conflictCells);
    }
}
