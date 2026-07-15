package com.example.attendance_system_java;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 教師マスタの業務ロジック層。
 * 今回は複雑な業務判断は無いが、3層構成に揃えるためService層を挟んでいる。
 * 「名前が空なら登録しない」といった入力チェックはここで行う。
 */
@Service
public class TeacherService {

    private final TeacherRepository teacherRepository;

    public TeacherService(TeacherRepository teacherRepository) {
        this.teacherRepository = teacherRepository;
    }

    public List<TeacherRepository.Teacher> findAll() {
        return teacherRepository.findAll();
    }

    /** 名前が空でなければ新規登録する */
    public void create(String teacherName) {
        if (teacherName == null || teacherName.isBlank()) {
            return;
        }
        teacherRepository.insert(teacherName.trim());
    }

    /** 名前が空でなければ更新する（有効/無効の切り替えもここで反映） */
    public void update(long teacherId, String teacherName, boolean isActive) {
        if (teacherName == null || teacherName.isBlank()) {
            return;
        }
        int isActiveValue;
        if (isActive) {
            isActiveValue = 1;
        } else {
            isActiveValue = 0;
        }
        teacherRepository.update(teacherId, teacherName.trim(), isActiveValue);
    }
}
