package com.example.attendance_system_java;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部屋（使用場所）マスタの業務ロジック層。
 * 構造は TeacherService とほぼ同じ。
 */
@Service
public class RoomService {

    private final RoomRepository roomRepository;

    public RoomService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    public List<RoomRepository.Room> findAll() {
        return roomRepository.findAll();
    }

    public void create(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            return;
        }
        roomRepository.insert(roomName.trim());
    }

    public void update(long roomId, String roomName, boolean isActive) {
        if (roomName == null || roomName.isBlank()) {
            return;
        }
        int isActiveValue;
        if (isActive) {
            isActiveValue = 1;
        } else {
            isActiveValue = 0;
        }
        roomRepository.update(roomId, roomName.trim(), isActiveValue);
    }
}
