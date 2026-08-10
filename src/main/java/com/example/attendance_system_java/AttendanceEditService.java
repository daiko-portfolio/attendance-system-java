package com.example.attendance_system_java;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 出欠編集画面の業務ロジック層。
 * 「出席時間から出席/欠席を判定する」という業務判断だけをここで行い、
 * DBへの実際の保存はAttendanceEditRepositoryに任せる。
 * AttendanceRegisterServiceと同じ判断ロジックを持つ。
 */
@Service
public class AttendanceEditService {

    private final AttendanceEditRepository attendanceEditRepository;

    public AttendanceEditService(AttendanceEditRepository attendanceEditRepository) {
        this.attendanceEditRepository = attendanceEditRepository;
    }

    /**
     * Controllerから渡される、生徒1人分の入力データ（生徒ID＋出席時間）。
     * まだ「出席」か「欠席」かは決まっていない、フォームそのままに近い形のデータ。
     */
    public record PersonHours(long personId, int attendedHours) {}

    public void updateAttendance(
            String attendanceDate,
            String checkNo,
            String lessonType,
            List<PersonHours> entries
    ) {
        List<AttendanceEditRepository.UpdateRow> rows = new ArrayList<>();

        for (PersonHours entry : entries) {

            // 出席時間が0なら欠席、それ以外は出席という業務ルール
            String status;
            if (entry.attendedHours() == 0) {
                status = "欠席";
            } else {
                status = "出席";
            }

            rows.add(new AttendanceEditRepository.UpdateRow(entry.personId(), status, entry.attendedHours()));
        }

        attendanceEditRepository.updateRows(attendanceDate, checkNo, lessonType, rows);
    }
}
