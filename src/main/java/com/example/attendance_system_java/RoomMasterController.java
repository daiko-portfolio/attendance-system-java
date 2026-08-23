package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * 部屋（使用場所）マスタ管理画面のController。
 * TeacherMasterControllerと同じ作りで、対象が部屋になっている。
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
    public String create(
            @RequestParam("room_name") String roomName,
            RedirectAttributes redirectAttributes
    ) {
        if (roomName == null || roomName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "部屋名が空欄のため、追加していません。");
            return "redirect:/rooms";
        }

        roomRepository.insert(roomName.trim());
        redirectAttributes.addFlashAttribute("successMessage", "部屋「" + roomName.trim() + "」を追加しました。");
        return "redirect:/rooms";
    }

    @PostMapping("/rooms/update")
    public String update(
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes
    ) {
        String roomName = allParams.get("room_name");
        if (roomName == null || roomName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "部屋名が空欄のため、更新していません。");
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
        redirectAttributes.addFlashAttribute("successMessage", "部屋「" + roomName.trim() + "」を更新しました。");
        return "redirect:/rooms";
    }
}
