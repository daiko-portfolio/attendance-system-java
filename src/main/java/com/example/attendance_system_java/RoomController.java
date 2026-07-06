package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 部屋（使用場所）マスタ管理画面のController。
 * TeacherControllerと同じ3層構成・同じ作りで、対象が部屋になっている。
 */
@Controller
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping("/rooms")
    public String rooms(Model model) {
        model.addAttribute("rooms", roomService.findAll());
        return "rooms";
    }

    @PostMapping("/rooms/create")
    public String create(@RequestParam("room_name") String roomName) {
        roomService.create(roomName);
        return "redirect:/rooms";
    }

    @PostMapping("/rooms/update")
    public String update(@RequestParam Map<String, String> allParams) {
        long roomId = Long.parseLong(allParams.get("room_id"));
        String roomName = allParams.get("room_name");
        boolean isActive = allParams.containsKey("is_active");

        roomService.update(roomId, roomName, isActive);
        return "redirect:/rooms";
    }
}
