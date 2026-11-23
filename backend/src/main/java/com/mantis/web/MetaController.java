package com.mantis.web;

import com.mantis.domain.Lookups;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serves the Mantis "enum" lists (status, priority, severity ...) to the Angular client. */
@RestController
@RequestMapping("/api/meta")
public class MetaController {
    @GetMapping
    public Map<String, Map<Integer, String>> meta() {
        Map<String, Map<Integer, String>> m = new LinkedHashMap<>();
        m.put("access", Lookups.ACCESS);
        m.put("status", Lookups.STATUS);
        m.put("resolution", Lookups.RESOLUTION);
        m.put("priority", Lookups.PRIORITY);
        m.put("severity", Lookups.SEVERITY);
        m.put("reproducibility", Lookups.REPRODUCIBILITY);
        m.put("projection", Lookups.PROJECTION);
        m.put("eta", Lookups.ETA);
        m.put("projectStatus", Lookups.PROJECT_STATUS);
        m.put("viewState", Lookups.VIEW_STATE);
        m.put("noteType", Lookups.NOTE_TYPE);
        m.put("relationship", Lookups.RELATIONSHIP);
        return m;
    }
}
