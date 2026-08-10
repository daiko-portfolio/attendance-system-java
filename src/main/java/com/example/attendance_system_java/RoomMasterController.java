package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 部屋（使用場所）マスタ管理画面のController。
 * TeacherMasterControllerと同じ2層構成・同じ作りで、対象が部屋になっている。
 * 業務判断が無い単純なCRUDなので、Serviceを挟まずRoomMasterRepositoryを直接呼んでいる。
 */
@Controller
public class RoomMasterController {

    private final RoomMasterRepository roomRepository;

    public RoomMasterController(RoomMasterRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @GetMapping("/rooms")
    public String rooms(Model model) {
        model.addAttribute("rooms", roomRepository.findAll());
        return "rooms";
    }

    @PostMapping("/rooms/create")
    public String create(@RequestParam("room_name") String roomName) {
        if (roomName == null || roomName.isBlank()) {
            return "redirect:/rooms";
        }
        roomRepository.insert(roomName.trim());
        return "redirect:/rooms";
    }

    @PostMapping("/rooms/update")
    public String update(@RequestParam Map<String, String> allParams) {
        String roomName = allParams.get("room_name");
        if (roomName == null || roomName.isBlank()) {
            return "redirect:/rooms";
        }

        long roomId = Long.parseLong(allParams.get("room_id"));

        int isActive;
        if (allParams.containsKey("is_active")) {
            isActive = 1;
        } else {
            isActive = 0;
        }

        roomRepository.update(roomId, roomName.trim(), isActive);
        return "redirect:/rooms";
    }
}
