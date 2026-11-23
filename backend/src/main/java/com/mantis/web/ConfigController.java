package com.mantis.web;

import com.mantis.dto.Dtos.AppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** feature switches the Angular client needs to know about */
@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final boolean timeTracking;

    public ConfigController(@Value("${app.time-tracking.enabled}") boolean timeTracking) { this.timeTracking = timeTracking; }

    @GetMapping
    public AppConfig config() { return new AppConfig(timeTracking); }
}
