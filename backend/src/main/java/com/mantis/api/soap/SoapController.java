package com.mantis.api.soap;

import com.mantis.domain.Issue;
import com.mantis.domain.Lookups;
import com.mantis.domain.Project;
import com.mantis.domain.User;
import com.mantis.dto.Dtos.IssueForm;
import com.mantis.dto.Dtos.IssueView;
import com.mantis.dto.Dtos.NoteForm;
import com.mantis.dto.Dtos.NoteView;
import com.mantis.dto.Dtos.UserRef;
import com.mantis.repo.ProjectRepository;
import com.mantis.service.AccessService;
import com.mantis.service.IssueExtrasService;
import com.mantis.service.IssueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * MantisConnect (SOAP 1.1) compatibility endpoint.
 *
 * <p>Requests are authenticated per call with the {@code username}/{@code password} elements that
 * MantisConnect sends in every operation body. All business rules, permissions and workflow checks
 * are delegated to the same services used by the web UI; this class only translates XML.</p>
 *
 * <p>Supported operations are listed in {@code docs/API_COMPATIBILITY.md} and in the WSDL served at
 * {@code GET /api/soap/mantisconnect.wsdl}. Unknown operations return a SOAP fault rather than a
 * made-up response.</p>
 */
@RestController
@RequestMapping("/api/soap")
@ConditionalOnProperty(name = "app.api.soap.enabled", havingValue = "true", matchIfMissing = true)
public class SoapController {
    private static final Logger log = LoggerFactory.getLogger(SoapController.class);
    private static final String NS = "http://futureware.biz/mantisconnect";

    private final SoapAuthService auth;
    private final IssueService issues;
    private final IssueExtrasService extras;
    private final AccessService access;
    private final ProjectRepository projects;

    public SoapController(SoapAuthService auth, IssueService issues, IssueExtrasService extras,
                          AccessService access, ProjectRepository projects) {
        this.auth = auth; this.issues = issues; this.extras = extras; this.access = access; this.projects = projects;
    }

    // ================================================================== transport

    @PostMapping(produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> handle(@RequestBody(required = false) String body) {
        try {
            if (body == null || body.isBlank()) throw new SoapFault("Client", "Empty SOAP request");
            Document doc = SoapXml.parse(body);
            Element op = SoapXml.operation(doc);
            String name = SoapXml.localName(op);
            String payload = dispatch(name, op);
            return envelope(name + "Response", payload);
        } catch (SoapFault f) {
            return fault(f.getCode(), f.getMessage());
        } catch (ResponseStatusException e) {
            return fault("Server", reason(e));
        } catch (Exception e) {
            log.error("Unexpected error in MantisConnect SOAP API", e);
            return fault("Server", "Internal server error");
        }
    }

    /** Serves the WSDL that describes the implemented subset. */
    @GetMapping(value = "/mantisconnect.wsdl", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> wsdl() {
        try {
            ClassPathResource resource = new ClassPathResource("soap/mantisconnect.wsdl");
            String wsdl = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.TEXT_XML).body(wsdl);
        } catch (Exception e) {
            log.error("Cannot read the MantisConnect WSDL", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ================================================================== dispatch

    private String dispatch(String name, Element op) {
        if ("mc_version".equals(name)) return "<return>1.0.0-mantis-java</return>";

        User me = auth.authenticate(SoapXml.trimmed(op, "username"), SoapXml.trimmed(op, "password"));

        return switch (name) {
            case "mc_login" -> "<return>" + userData(me) + "</return>";
            case "mc_projects_get_user_accessible" -> "<return>" + projectList(me) + "</return>";
            case "mc_project_get_issues" -> "<return>" + projectIssues(me, op) + "</return>";
            case "mc_issue_get" -> "<return>" + issueData(me, requiredIssueId(op)) + "</return>";
            case "mc_issue_add" -> "<return>" + issueAdd(me, op) + "</return>";
            case "mc_issue_update" -> "<return>" + issueUpdate(me, op) + "</return>";
            case "mc_issue_delete" -> "<return>" + issueDelete(me, op) + "</return>";
            case "mc_issue_notes_get" -> "<return>" + noteList(me, requiredIssueId(op)) + "</return>";
            case "mc_issue_note_add" -> "<return>" + noteAdd(me, op) + "</return>";
            default -> throw new SoapFault("Client", "Unsupported MantisConnect operation: " + name);
        };
    }

    // ================================================================== operations

    private String projectList(User me) {
        Map<Long, Integer> levels = access.levels(me);
        StringBuilder b = new StringBuilder();
        for (Project p : projects.findAllByOrderByName()) {
            if (!levels.containsKey(p.getId())) continue;
            b.append("<item>");
            b.append("<id>").append(p.getId()).append("</id>");
            b.append("<name>").append(SoapXml.esc(p.getName())).append("</name>");
            SoapMapper.appendEnum(b, "status", Lookups.PROJECT_STATUS, p.getStatus());
            b.append("<enabled>").append(p.isEnabled()).append("</enabled>");
            SoapMapper.appendEnum(b, "view_state", Lookups.VIEW_STATE, p.getViewState());
            b.append("<description>").append(SoapXml.esc(p.getDescription())).append("</description>");
            b.append("</item>");
        }
        return b.toString();
    }

    private String projectIssues(User me, Element op) {
        Long projectId = SoapXml.longValue(op, "project_id");
        if (projectId == null) throw new SoapFault("Client", "project_id is required");
        Project p = access.projectOrThrow(projectId);
        access.require(me, p, Lookups.VIEWER);

        int page = positive(SoapXml.intValue(op, "page_number"), 1);
        int perPage = Math.min(positive(SoapXml.intValue(op, "per_page"), 50), 100);
        PageRequest pg = PageRequest.of(page - 1, perPage, Sort.by(Sort.Direction.DESC, "lastUpdated"));
        IssueService.Filter filter = new IssueService.Filter(projectId, null, null, null, null, null, null,
                null, false, null);
        Page<IssueView> result = issues.searchViews(me, filter, pg);

        StringBuilder b = new StringBuilder();
        for (IssueView v : result.getContent()) b.append("<item>").append(issueData(v, null)).append("</item>");
        return b.toString();
    }

    private String issueData(User me, Long id) {
        IssueView view = issues.viewById(me, id);
        List<NoteView> notes = extras.listNotes(me, id);
        return issueData(view, notes);
    }

    private String issueData(IssueView view, List<NoteView> notes) {
        StringBuilder b = new StringBuilder();
        SoapMapper.appendIssue(b, view, notes);
        return b.toString();
    }

    private String issueAdd(User me, Element op) {
        IssueForm form = SoapMapper.toForm(SoapXml.child(op, "issue"));
        Issue created = issues.create(me, form);
        return String.valueOf(created.getId());
    }

    private String issueUpdate(User me, Element op) {
        Long id = requiredIssueId(op);
        issues.update(me, id, SoapMapper.toForm(SoapXml.child(op, "issue")));
        return "true";
    }

    private String issueDelete(User me, Element op) {
        issues.delete(me, requiredIssueId(op));
        return "true";
    }

    private String noteList(User me, Long issueId) {
        StringBuilder b = new StringBuilder();
        for (NoteView n : extras.listNotes(me, issueId)) SoapMapper.appendNote(b, n);
        return b.toString();
    }

    private String noteAdd(User me, Element op) {
        Long issueId = requiredIssueId(op);
        Element note = SoapXml.child(op, "note");
        if (note == null) throw new SoapFault("Client", "note is required");
        String text = SoapXml.text(note, "text");
        if (text == null || text.isBlank()) throw new SoapFault("Client", "note.text is required");
        Integer time = SoapXml.intValue(note, "time_tracking");
        NoteForm form = new NoteForm(text, SoapMapper.noteViewState(note), SoapMapper.noteType(note), time);
        NoteView created = extras.addNote(me, issueId, form);
        return String.valueOf(created.id());
    }

    // ================================================================== helpers

    private static Long requiredIssueId(Element op) {
        Long id = SoapXml.longValue(op, "issue_id");
        if (id == null) throw new SoapFault("Client", "issue_id is required");
        return id;
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static String userData(User u) {
        UserRef ref = new UserRef(u.getId(), u.getUsername(), u.getRealname());
        StringBuilder b = new StringBuilder();
        SoapMapper.appendUser(b, ref);
        b.append("<email>").append(SoapXml.esc(u.getEmail())).append("</email>");
        b.append("<access_level>").append(u.getAccessLevel()).append("</access_level>");
        b.append("<enabled>").append(u.isEnabled()).append("</enabled>");
        return b.toString();
    }

    private static String reason(ResponseStatusException e) {
        if (e.getReason() != null) return e.getReason();
        int status = e.getStatusCode().value();
        return switch (status) {
            case 401 -> "Access denied";
            case 403 -> "Access denied";
            case 404 -> "Not found";
            default -> "Request failed";
        };
    }

    private static ResponseEntity<String> envelope(String responseName, String payload) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:ns1=\"" + NS + "\">"
                + "<SOAP-ENV:Body><ns1:" + responseName + ">" + payload + "</ns1:" + responseName + ">"
                + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_XML).body(xml);
    }

    private static ResponseEntity<String> fault(String code, String message) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<SOAP-ENV:Body><SOAP-ENV:Fault>"
                + "<faultcode>SOAP-ENV:" + SoapXml.esc(code) + "</faultcode>"
                + "<faultstring>" + SoapXml.esc(message) + "</faultstring>"
                + "</SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_XML).body(xml);
    }
}
