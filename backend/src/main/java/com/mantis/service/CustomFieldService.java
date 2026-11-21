package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.CustomFieldProjectRepository;
import com.mantis.repo.CustomFieldValueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/** Port of Mantis custom_field_api.php: definitions per project, per-issue values, validation, access levels. */
@Service
@Transactional
public class CustomFieldService {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final CustomFieldProjectRepository links;
    private final CustomFieldValueRepository values;
    private final HistoryService history;

    public CustomFieldService(CustomFieldProjectRepository links, CustomFieldValueRepository values, HistoryService history) {
        this.links = links; this.values = values; this.history = history;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    public static List<String> options(CustomField f) {
        if (f.getPossibleValues() == null || f.getPossibleValues().isBlank()) return List.of();
        return Arrays.stream(f.getPossibleValues().split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static CustomFieldView view(CustomField f) {
        return new CustomFieldView(f.getId(), f.getName(), f.getType(), f.getPossibleValues(), f.getDefaultValue(),
                f.getValidRegexp(), f.getAccessLevelR(), f.getAccessLevelRw(), f.getLengthMin(), f.getLengthMax(),
                f.isRequireReport(), f.isRequireUpdate(), f.isDisplayReport(), f.isDisplayUpdate(),
                f.isDisplayResolved(), f.isDisplayClosed(), f.isFilterBy());
    }

    // ------------------------------------------------------------------ read side
    /** fields shown on an existing issue (only those the user may read) */
    @Transactional(readOnly = true)
    public List<IssueFieldValue> forIssue(Issue i, int lvl) {
        Map<Long, String> vals = values.findByIssueId(i.getId()).stream()
                .collect(Collectors.toMap(v -> v.getField().getId(), CustomFieldValue::getValue, (a, b) -> a));
        List<IssueFieldValue> out = new ArrayList<>();
        for (CustomFieldProject l : links.findByProjectIdOrderBySequenceAscIdAsc(i.getProject().getId())) {
            CustomField f = l.getField();
            if (lvl < f.getAccessLevelR()) continue;
            out.add(new IssueFieldValue(f.getId(), f.getName(), f.getType(), vals.getOrDefault(f.getId(), ""),
                    options(f), f.isRequireUpdate(), lvl >= f.getAccessLevelRw() && f.isDisplayUpdate()));
        }
        return out;
    }

    /** fields offered on the "report issue" form, pre-filled with default values */
    @Transactional(readOnly = true)
    public List<IssueFieldValue> forReport(Project p, int lvl) {
        List<IssueFieldValue> out = new ArrayList<>();
        for (CustomFieldProject l : links.findByProjectIdOrderBySequenceAscIdAsc(p.getId())) {
            CustomField f = l.getField();
            if (!f.isDisplayReport() || lvl < f.getAccessLevelRw()) continue;
            out.add(new IssueFieldValue(f.getId(), f.getName(), f.getType(), f.getDefaultValue() == null ? "" : f.getDefaultValue(),
                    options(f), f.isRequireReport(), true));
        }
        return out;
    }

    // ------------------------------------------------------------------ write side
    /**
     * Validates and stores the submitted values.
     * @param input fieldId -> value (may be null); only fields linked to the project and writable by the user are touched
     */
    public void apply(User me, Issue i, Map<Long, String> input, boolean creating, int lvl) {
        List<CustomFieldProject> ls = links.findByProjectIdOrderBySequenceAscIdAsc(i.getProject().getId());
        if (ls.isEmpty()) return;
        Map<Long, CustomFieldValue> existing = values.findByIssueId(i.getId()).stream()
                .collect(Collectors.toMap(v -> v.getField().getId(), v -> v, (a, b) -> a));
        Map<Long, String> current = new HashMap<>();
        existing.forEach((k, v) -> current.put(k, v.getValue()));

        for (CustomFieldProject l : ls) {
            CustomField f = l.getField();
            boolean writable = lvl >= f.getAccessLevelRw() && (creating ? f.isDisplayReport() : f.isDisplayUpdate());
            if (!writable) continue;
            String nv;
            if (input != null && input.containsKey(f.getId())) nv = input.get(f.getId()) == null ? "" : input.get(f.getId()).trim();
            else if (creating) nv = f.getDefaultValue() == null ? "" : f.getDefaultValue();
            else continue;

            validate(f, nv);
            String old = current.getOrDefault(f.getId(), "");
            if (!old.equals(nv)) {
                CustomFieldValue v = existing.get(f.getId());
                if (v == null) { v = new CustomFieldValue(); v.setField(f); v.setIssue(i); }
                v.setValue(nv);
                values.save(v);
                history.log(me, i, f.getName(), old.isEmpty() ? null : old, nv.isEmpty() ? null : nv);
                current.put(f.getId(), nv);
            }
        }
        // required fields must have a value after the change
        for (CustomFieldProject l : ls) {
            CustomField f = l.getField();
            boolean writable = lvl >= f.getAccessLevelRw() && (creating ? f.isDisplayReport() : f.isDisplayUpdate());
            boolean required = creating ? f.isRequireReport() : f.isRequireUpdate();
            if (writable && required && current.getOrDefault(f.getId(), "").isBlank())
                throw bad("Custom field \"" + f.getName() + "\" is required");
        }
    }

    /** Port of custom_field_validate(): throws 400 when the value does not fit the field definition. */
    public static void validate(CustomField f, String v) {
        if (v == null || v.isEmpty()) return;
        String n = f.getName();
        if (v.length() > 10_000) throw bad("Custom field \"" + n + "\" is too long");
        switch (f.getType()) {
            case CustomField.NUMERIC -> {
                if (!v.matches("-?\\d+")) throw bad("\"" + n + "\" must be a whole number");
                bounds(f, Double.parseDouble(v));
            }
            case CustomField.FLOAT -> {
                try { bounds(f, Double.parseDouble(v)); }
                catch (NumberFormatException e) { throw bad("\"" + n + "\" must be a number"); }
            }
            case CustomField.EMAIL -> { if (!EMAIL.matcher(v).matches()) throw bad("\"" + n + "\" must be an e-mail address"); }
            case CustomField.DATE -> {
                try { LocalDate.parse(v); } catch (DateTimeParseException e) { throw bad("\"" + n + "\" must be a date (yyyy-MM-dd)"); }
            }
            case CustomField.ENUM, CustomField.LIST, CustomField.RADIO -> {
                if (!options(f).contains(v)) throw bad("\"" + n + "\": invalid choice");
            }
            case CustomField.CHECKBOX, CustomField.MULTILIST -> {
                List<String> opts = options(f);
                for (String part : v.split("\\|")) if (!part.isEmpty() && !opts.contains(part)) throw bad("\"" + n + "\": invalid choice");
            }
            default -> {   // string, textarea
                if (f.getLengthMin() > 0 && v.length() < f.getLengthMin()) throw bad("\"" + n + "\" is too short (min " + f.getLengthMin() + ")");
                if (f.getLengthMax() > 0 && v.length() > f.getLengthMax()) throw bad("\"" + n + "\" is too long (max " + f.getLengthMax() + ")");
                if (f.getValidRegexp() != null && !f.getValidRegexp().isBlank()) {
                    try { if (!v.matches(f.getValidRegexp())) throw bad("\"" + n + "\" has an invalid format"); }
                    catch (PatternSyntaxException e) { /* broken admin regexp: do not block users */ }
                }
            }
        }
    }

    private static void bounds(CustomField f, double d) {
        if (f.getLengthMin() != 0 && d < f.getLengthMin()) throw bad("\"" + f.getName() + "\" must be at least " + f.getLengthMin());
        if (f.getLengthMax() != 0 && d > f.getLengthMax()) throw bad("\"" + f.getName() + "\" must be at most " + f.getLengthMax());
    }
}
