package com.example.attendance_system_java;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 出欠登録画面の業務ロジック層。
 * 「出席時間から出席/欠席を判定する」という業務判断だけをここで行い、
 * DBへの実際の保存はAttendanceRegisterRepositoryに任せる。
 */
@Service
public class AttendanceRegisterService {

    private final AttendanceRegisterRepository registerRepository;

    public AttendanceRegisterService(AttendanceRegisterRepository registerRepository) {
        this.registerRepository = registerRepository;
    }

    /**
     * Controllerから渡される、生徒1人分の入力データ（生徒ID＋出席時間）。
     * まだ「出席」か「欠席」かは決まっていない、フォームそのままに近い形のデータ。
     */
    public record PersonHours(long personId, int attendedHours) {}

    /**
     * 1日・1区分分の出欠をまとめて登録する。
     * entries の人数分だけ、1人ずつRepositoryのupsertAttendance()を呼び出す
     * （スケジュール機能のような「1件でも重複したら全部取り消す」という要件が無いため、
     *  ここでは元のFlask版と同じく、1人ずつ個別に登録している）。
     */
    public void registerAttendance(
            String attendanceDate,
            String checkNo,
            String lessonType,
            List<PersonHours> entries
    ) {

        for (PersonHours entry : entries) {

            // 出席時間が0なら欠席、それ以外は出席という業務ルール
            String status;
            if (entry.attendedHours() == 0) {
                status = "欠席";
            } else {
                status = "出席";
            }

            registerRepository.upsertAttendance(
                    attendanceDate,
                    checkNo,
                    entry.personId(),
                    status,
                    lessonType,
                    entry.attendedHours()
            );
        }
    }
}
