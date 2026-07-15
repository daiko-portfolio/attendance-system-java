package com.example.attendance_system_java;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * メニュー画面の「サンプルデータ投入」ボタンから呼ばれるController。
 * 業務判断は無く、ただSampleDataServiceを呼ぶだけなのでシンプルな作り。
 */
@Controller
public class SampleDataController {

    private final SampleDataService sampleDataService;

    public SampleDataController(SampleDataService sampleDataService) {
        this.sampleDataService = sampleDataService;
    }

    @PostMapping("/sample-data/load")
    public String load() {
        sampleDataService.load();
        // PRG（Post-Redirect-Get）パターン：POST後は必ずGETへリダイレクトする
        return "redirect:/?sampleDataLoaded=true";
    }
}
