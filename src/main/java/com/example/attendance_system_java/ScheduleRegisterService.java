package com.example.attendance_system_java;

import org.springframework.stereotype.Service;

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
 * ■ 登録の方式：消してから入れ直す（DELETE→INSERT）
 * 画面はその週×対象クラスの全コマを毎回まるごと送信してくるため、
 * DB側も「対象週×対象クラスの範囲を全部消して、送られてきた内容を全部入れる」
 * という入れ替え方式にしている。これにより
 *   ・DBに残った古い行と照合する2回目のチェックが不要になる
 *   ・「教師を別クラスへ付け替える」操作が1回の登録で済む
 *   ・セルを空に戻して登録すれば、そのコマの登録を取り消せる
 * というシンプルな動きになる。入れ替えの途中で失敗した時に「消しただけ」の状態に
 * ならないよう、DELETEとINSERTはRepository側で1つのトランザクションとして実行される。
 */
@Service
public class ScheduleRegisterService {

    private final ScheduleRegisterRepository scheduleRepository;

    public ScheduleRegisterService(ScheduleRegisterRepository scheduleRepository) {
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
     * 重複が1件でもあれば全体を登録しない。
     *
     * @param entries   登録する全コマ（休みのコマ含む。教師が未選択のコマは含まれない）
     * @param classIds  今回の登録対象クラスのID一覧（入れ替えでDELETEする範囲の指定に使う）
     * @param checkNos  今回の登録対象の区分一覧（1=午前, 2=午後。入れ替えでDELETEする範囲の指定に使う）
     * @param weekStart 対象週の月曜日（yyyy-MM-dd）
     * @param weekEnd   対象週の日曜日（yyyy-MM-dd）
     */
    public RegisterResult registerWeek(
            List<CellEntry> entries,
            List<Long> classIds,
            List<Integer> checkNos,
            String weekStart,
            String weekEnd
    ) {

        List<String> errorMessages = new ArrayList<>();
        Set<String> conflictCells = new HashSet<>();

        // 教師名・場所名をメッセージ表示用に引けるようにしておく
        // （IDだけだとエラーメッセージが「教師ID:2が重複」のようになり分かりにくいため、
        //   先に teacherId -> teacherName の対応表を作っておく）
        Map<Long, String> teacherNames = new HashMap<>();
        for (ScheduleRegisterRepository.Teacher teacher : scheduleRepository.findActiveTeachers()) {
            teacherNames.put(teacher.teacherId(), teacher.teacherName());
        }

        Map<Long, String> roomNames = new HashMap<>();
        for (ScheduleRegisterRepository.Room room : scheduleRepository.findActiveRooms()) {
            roomNames.put(room.roomId(), room.roomName());
        }

        // ---- 重複チェック：送信フォーム内での二重登場 ----
        // 「今回送られてきた全コマ」の中で、同じ教師・同じ場所が
        // 「同じ日付・同じ区分」に2回以上登場していないかを調べる。
        // 「教師ID_日付_区分」というキー文字列を作り、Mapに既に同じキーがあれば重複、
        // という仕組みで判定している（DBに問い合わせなくても分かるチェック）。
        //
        // DBに保存済みのデータとの照合はここでは行わない。対象範囲はこの後まるごと
        // 消して入れ直すため、古い行と照合する意味が無いからである。
        // フォームの範囲外（無効化した旧クラスなど）との衝突だけはこのチェックを
        // すり抜けるが、そこはDBのUNIQUE制約が拒否し、トランザクションが巻き戻す。
        Map<String, CellEntry> seenTeachers = new HashMap<>();
        Map<String, CellEntry> seenRooms = new HashMap<>();

        for (CellEntry entry : entries) {

            // 「休み」のコマは教師・場所を持たないのでチェック対象外。
            // 「休講」は教師・場所の情報を持つが、このアプリでは休講を「災害等でその日全クラスを
            // 一斉に休講にする」時にしか使わない運用を前提にしており、同じ時間帯に他クラスの
            // 「通常」授業が並行して存在することは無いため、重複チェックの対象外にしている
            // （もし将来「特定クラスだけ休講にして教師・場所を別クラスへ回す」運用を追加するなら、
            //  ここで休講も重複チェックの対象に含める必要がある）
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

        // 重複が1つでもあれば、ここで処理を打ち切って登録を一切行わない
        // （success=false を返すのでControllerはこの週を保存せず、エラー内容だけ画面へ戻す）
        if (!errorMessages.isEmpty()) {
            return new RegisterResult(false, errorMessages, conflictCells);
        }

        // ---- 登録処理 ----
        // CellEntry（画面の事情を含むデータ）を、DB保存用のScheduleRowに詰め替えてから
        // Repositoryに渡す。「対象週×対象クラスを全部消して、この内容を全部入れる」
        // という入れ替えは、Repository側で1つのトランザクションとして実行される。
        List<ScheduleRegisterRepository.ScheduleRow> rows = new ArrayList<>();

        for (CellEntry entry : entries) {

            if (entry.status().equals("休み")) {
                // 休みは教師・場所を持たない（そもそも授業日でないため）
                rows.add(new ScheduleRegisterRepository.ScheduleRow(
                        entry.classId(), entry.scheduleDate(), entry.checkNo(),
                        "休み", null, null, null, entry.memo()
                ));
            } else if (entry.status().equals("休講")) {
                // 休講は「本来の予定」を記録として残すため、教師・場所・属性はNULLにせずそのまま保存する
                rows.add(new ScheduleRegisterRepository.ScheduleRow(
                        entry.classId(), entry.scheduleDate(), entry.checkNo(),
                        "休講", entry.lessonType(), entry.teacherId(), entry.roomId(), entry.memo()
                ));
            } else {
                rows.add(new ScheduleRegisterRepository.ScheduleRow(
                        entry.classId(), entry.scheduleDate(), entry.checkNo(),
                        "通常", entry.lessonType(), entry.teacherId(), entry.roomId(), entry.memo()
                ));
            }
        }

        scheduleRepository.replaceWeekSchedules(classIds, checkNos, weekStart, weekEnd, rows);

        return new RegisterResult(true, errorMessages, conflictCells);
    }
}
