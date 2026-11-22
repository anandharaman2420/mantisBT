package com.mantis.api.soap;

import com.mantis.domain.Lookups;
import com.mantis.dto.Dtos.IssueForm;
import com.mantis.dto.Dtos.IssueView;
import com.mantis.dto.Dtos.NoteView;
import com.mantis.dto.Dtos.UserRef;
import org.w3c.dom.Element;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts between MantisConnect XML fragments and the application DTOs.
 *
 * <p>The wire format follows the MantisConnect conventions: structs contain {@code id}/{@code name}
 * pairs for enums and {@code id}/{@code name}/{@code real_name}/{@code email} for users. Only the
 * subset of fields that the application actually stores is produced; unsupported fields are omitted
 * rather than invented (see docs/API_COMPATIBILITY.md).</p>
 */
final class SoapMapper {
    private SoapMapper() {}

    // ================================================================== output

    static void appendUser(StringBuilder b, UserRef u) {
        if (u == null) return;
        el(b, "id", u.id());
        el(b, "name", u.username());
        el(b, "real_name", u.realname());
    }

    static void appendProjectRef(StringBuilder b, Long id, String name) {
        if (id == null && name == null) return;
        b.append("<project>");
        el(b, "id", id);
        el(b, "name", name);
        b.append("</project>");
    }

    static void appendEnum(StringBuilder b, String field, Map<Integer, String> values, int value) {
        String label = Lookups.label(values, value);
        b.append('<').append(field).append('>');
        el(b, "id", value);
        el(b, "name", label);
        el(b, "label", label);
        b.append("</").append(field).append('>');
    }

    static void appendNote(StringBuilder b, NoteView n) {
        b.append("<item>");
        el(b, "id", n.id());
        if (n.reporter() != null) {
            b.append("<reporter>");
            appendUser(b, n.reporter());
            b.append("</reporter>");
        }
        el(b, "text", n.text());
        appendEnum(b, "view_state", Lookups.VIEW_STATE, n.viewState());
        appendEnum(b, "note_type", Lookups.NOTE_TYPE, n.noteType());
        el(b, "date_submitted", n.dateSubmitted());
        el(b, "last_modified", n.lastModified());
        b.append("</item>");
    }

    static void appendIssue(StringBuilder b, IssueView v, List<NoteView> notes) {
        el(b, "id", v.id());
        appendProjectRef(b, v.projectId(), v.projectName());
        if (v.categoryId() != null) {
            b.append("<category>");
            el(b, "id", v.categoryId());
            el(b, "name", v.category());
            b.append("</category>");
        }
        if (v.reporter() != null) {
            b.append("<reporter>");
            appendUser(b, v.reporter());
            b.append("</reporter>");
        }
        if (v.handler() != null) {
            b.append("<handler>");
            appendUser(b, v.handler());
            b.append("</handler>");
        }
        el(b, "summary", v.summary());
        el(b, "description", v.description());
        el(b, "steps_to_reproduce", v.stepsToReproduce());
        el(b, "additional_information", v.additionalInformation());
        appendEnum(b, "status", Lookups.STATUS, v.status());
        appendEnum(b, "resolution", Lookups.RESOLUTION, v.resolution());
        appendEnum(b, "priority", Lookups.PRIORITY, v.priority());
        appendEnum(b, "severity", Lookups.SEVERITY, v.severity());
        appendEnum(b, "reproducibility", Lookups.REPRODUCIBILITY, v.reproducibility());
        appendEnum(b, "projection", Lookups.PROJECTION, v.projection());
        appendEnum(b, "eta", Lookups.ETA, v.eta());
        appendEnum(b, "view_state", Lookups.VIEW_STATE, v.viewState());
        el(b, "os", v.os());
        el(b, "os_build", v.osBuild());
        el(b, "platform", v.platform());
        el(b, "version", v.version());
        el(b, "fixed_in_version", v.fixedInVersion());
        el(b, "target_version", v.targetVersion());
        el(b, "build", v.build());
        el(b, "sticky", v.sticky());
        el(b, "date_submitted", v.dateSubmitted());
        el(b, "last_updated", v.lastUpdated());
        el(b, "time_tracking", v.timeMinutes());

        if (v.tags() != null && !v.tags().isEmpty()) {
            b.append("<tags>");
            for (String name : v.tags()) {
                b.append("<item>");
                el(b, "name", name);
                b.append("</item>");
            }
            b.append("</tags>");
        }
        if (notes != null) {
            b.append("<notes>");
            notes.forEach(n -> appendNote(b, n));
            b.append("</notes>");
        }
    }

    // ================================================================== input

    static IssueForm toForm(Element issue) {
        if (issue == null) throw new SoapFault("Client", "Issue data is required");
        Long projectId = refId(SoapXml.child(issue, "project"));
        Long categoryId = refId(SoapXml.child(issue, "category"));
        Element handler = SoapXml.child(issue, "handler");
        Long handlerId = refId(handler);
        boolean clearHandler = handler != null && handlerId != null && handlerId == 0L;
        if (clearHandler) handlerId = null;

        return new IssueForm(
                projectId, categoryId,
                SoapXml.trimmed(issue, "summary"),
                SoapXml.text(issue, "description"),
                SoapXml.text(issue, "steps_to_reproduce"),
                SoapXml.text(issue, "additional_information"),
                enumValue(issue, "priority", Lookups.PRIORITY),
                enumValue(issue, "severity", Lookups.SEVERITY),
                enumValue(issue, "reproducibility", Lookups.REPRODUCIBILITY),
                enumValue(issue, "status", Lookups.STATUS),
                enumValue(issue, "resolution", Lookups.RESOLUTION),
                enumValue(issue, "projection", Lookups.PROJECTION),
                enumValue(issue, "eta", Lookups.ETA),
                handlerId, clearHandler ? Boolean.TRUE : null, null,
                SoapXml.text(issue, "os"), SoapXml.text(issue, "os_build"),
                SoapXml.text(issue, "platform"), SoapXml.text(issue, "version"),
                SoapXml.text(issue, "fixed_in_version"), SoapXml.text(issue, "target_version"),
                SoapXml.text(issue, "build"),
                enumValue(issue, "view_state", Lookups.VIEW_STATE),
                SoapXml.boolValue(issue, "sticky"),
                tagNames(issue), null);
    }

    static Integer noteViewState(Element note) {
        return enumValue(note, "view_state", Lookups.VIEW_STATE);
    }

    static Integer noteType(Element note) {
        return enumValue(note, "note_type", Lookups.NOTE_TYPE);
    }

    /** Reads an id from a struct such as {@code <project><id>1</id></project>}. */
    private static Long refId(Element ref) {
        if (ref == null) return null;
        return SoapXml.longValue(ref, "id");
    }

    /** Reads an enum either by numeric {@code id} or by {@code name}/{@code label}. */
    static Integer enumValue(Element parent, String field, Map<Integer, String> values) {
        Element e = SoapXml.child(parent, field);
        if (e == null) return null;
        Integer id = SoapXml.intValue(e, "id");
        if (id != null) return id;
        String name = SoapXml.trimmed(e, "name");
        if (name == null) name = SoapXml.trimmed(e, "label");
        if (name == null || name.isEmpty()) return null;
        for (Map.Entry<Integer, String> en : values.entrySet())
            if (en.getValue().equalsIgnoreCase(name)) return en.getKey();
        throw new SoapFault("Client", "Unknown value '" + name + "' for field '" + field + "'");
    }

    /** {@code null} when the request has no {@code tags} element (meaning "leave unchanged"). */
    private static List<String> tagNames(Element issue) {
        Element tags = SoapXml.child(issue, "tags");
        if (tags == null) return null;
        List<String> names = new ArrayList<>();
        for (Element item : SoapXml.children(tags)) {
            String name = SoapXml.trimmed(item, "name");
            if (name == null || name.isBlank()) name = item.getTextContent() == null ? null : item.getTextContent().trim();
            if (name != null && !name.isBlank()) names.add(name);
        }
        return names;
    }

    private static void el(StringBuilder b, String name, Object value) {
        if (value == null) return;
        b.append('<').append(name).append('>')
                .append(SoapXml.esc(value instanceof Instant ? value.toString() : String.valueOf(value)))
                .append("</").append(name).append('>');
    }
}
